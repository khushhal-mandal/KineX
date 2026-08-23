# deploy/

Phase 7. k3s manifests for the single Oracle Cloud Always Free ARM instance, plus the
`tls/` issuers that need cert-manager installed first.

Everything lives in one namespace, so `kubectl get all -n kinex` is a complete inventory
and `kubectl delete ns kinex` is a complete teardown. The root design doc is the
authority on what is settled; the backend design doc covers the application these
manifests run.

---

## Applying it

The manifests are numbered so that a plain `kubectl apply -f deploy/k3s/` works in one
pass — but the Secret is not in git and has to exist first.

```
cp deploy/secret.example.yaml deploy/k3s/03-secret.yaml
$EDITOR deploy/k3s/03-secret.yaml          # three generated values, one Groq key
kubectl apply -f deploy/k3s/
```

`deploy/k3s/03-secret.yaml` is gitignored. The example sits one directory **above** the
manifests on purpose: `kubectl apply -f deploy/k3s/` applies every YAML in that directory,
so an example living next to the real thing would overwrite a working secret with
placeholders — and the only symptom would be every pod failing to authenticate against a
database whose password had not changed.

`apply -f` on a directory is **not recursive**, which is what keeps `tls/` out of a normal
apply. That is deliberate: those two resources need cert-manager's CRDs installed or they
fail on an unknown kind.

| File | What |
| --- | --- |
| `00-namespace.yaml` | the `kinex` namespace |
| `01-config.yaml` | non-secret configuration, one ConfigMap for API, CronJob and puller |
| `03-secret.yaml` | **not in git** — copy from `../secret.example.yaml` |
| `10-postgres.yaml` | PVC, ClusterIP Service, Deployment |
| `20-api.yaml` | Service, Deployment, and the migration as an initContainer |
| `30-ollama.yaml` | PVC, ClusterIP Service, Deployment |
| `31-ollama-pull.yaml` | one-shot Job, pulls both models into the volume |
| `40-cronjob-summarize.yaml` | nightly narratives, 03:00 UTC |
| `41-cronjob-backup.yaml` | `pg_dump` to a PVC, 04:30 UTC |
| `50-ingress.yaml` | Traefik Ingress — **the one file with a value you must edit** |
| `tls/clusterissuer.yaml` | cert-manager issuers, applied separately |

### The two values that are placeholders

`api.kinex.example` in `50-ingress.yaml`, and `replace-me@example.com` in
`tls/clusterissuer.yaml`. `.example` is reserved by RFC 2606 and can never resolve to
anyone's real host, which is the point of using it: a manifest applied unedited fails to
route rather than quietly reaching a stranger's server.

### TLS, which is a second step

cert-manager brings CRDs, so `tls/` cannot be applied until it is installed and its
webhook is serving. The rollout wait is not optional — applying a ClusterIssuer before the
webhook answers fails with a connection refused that reads like a broken manifest.

```
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.19.1/cert-manager.yaml
kubectl -n cert-manager rollout status deploy/cert-manager-webhook
kubectl apply -f deploy/k3s/tls/
```

Point the Ingress annotation at `letsencrypt-staging` first and confirm a certificate is
issued. Production rate-limits five failures per account per hostname per hour, and a
wrong DNS record burns through that in an afternoon — after which the fix is to wait, not
to try harder. Staging certificates are untrusted by browsers, which is the whole of the
difference.

Without cert-manager the annotation is inert and Traefik serves its own self-signed
certificate. That is what makes this directory applyable to a laptop k3d cluster
unmodified, which is how it is verified.

---

## The resource budget

The box is **2 OCPU / 12 GB** — Oracle halved the Always Free ARM shape in June 2026, so
anything written against 4 OCPU / 24 GB is out of date.

Requests are what the scheduler reserves and what decides whether a pod fits. Limits are
ceilings. Measured from a live cluster with `kubectl describe node`, not added up by hand:

| | CPU requests | Memory requests | CPU limits | Memory limits |
| --- | --- | --- | --- | --- |
| postgres | 200m | 512Mi | 1000m | 2Gi |
| kinex-api | 100m | 256Mi | 500m | 768Mi |
| ollama | 200m | 512Mi | 1500m | 5Gi |
| k3s system (coredns, metrics-server) | 200m | 140Mi | — | 170Mi |
| **total** | **700m** (35%) | **1420Mi** (12%) | **3000m** (150%) | **8106Mi** (66%) |

Percentages are against 2 OCPU / 12 GB.

**CPU limits deliberately total 150% of the box.** They are ceilings on processes that do
not peak together: the summarize CronJob runs at 3am when nobody is chatting, and the API
is waiting on Postgres or on Groq for most of any request it serves. Sizing limits so
their sum fits would cap each service below what it can use when it is the only thing
running, which is the common case on a one-user box.

**Memory is the resource that actually binds**, and it is sized so that every ceiling
being hit at once still fits in 12 GB with ~4 GB spare. Ollama has the largest ceiling
because a loaded `qwen2.5:3b` is ~2 GB resident and inference needs headroom above it; its
*request* is small because models unload after five idle minutes, and reserving 5 GiB
against the scheduler all day would be reserving it against the API.

The three CronJob pods (summarize, backup, ollama-pull) are transient and are not in the
table. Together they request under 200m and 400Mi, and `concurrencyPolicy: Forbid` on both
CronJobs means at most one of each exists at a time.

### On a laptop k3d cluster this does not all apply

Docker Desktop's VM here is ~3.8 GiB, so the node reports **3.8 GiB allocatable** and
ollama's 5Gi limit is 130% of the whole node. The manifests still apply and run — a limit
above node capacity is legal and simply unreachable — but a local cluster cannot exercise
the memory ceilings, and an OOM here is not evidence about the Oracle box.

---

## Migrations run as an initContainer, and that constrains scaling

`20-api.yaml` runs `alembic upgrade head` as an initContainer on the API pod rather than
as a standalone Job. Two reasons, and the second is the one that decided it:

- compose has `api` depend on `migrate` with `service_completed_successfully`, so a failed
  migration keeps the API down instead of letting it serve against a schema that is not
  there. Nothing makes a Deployment wait for a Job, so a Job does not reproduce that — the
  API would come up alongside it and answer requests against whatever schema existed.
- **A Job does not re-run on a rollout.** Deploying a new image that carries a new
  revision would leave the Job in its completed state from the previous deploy and start
  the new code against an unmigrated schema. An initContainer runs on every pod start, and
  `alembic upgrade head` against an already-migrated database is a no-op costing about a
  second.

The initContainer receives `KINEX_DATABASE_URL` and nothing else. That is deliberate:
`env.py` imports the narrow `sqlalchemy_dsn_from_env()` rather than building `Settings`,
so a migration needs a database URL and not `KINEX_JWT_SECRET` — a key that mints tokens —
nor the Groq key. Handing this container the whole Secret would quietly undo that.

> **Scaling the API past one replica requires moving migrations out of the init path
> first.** The initContainer runs once per pod, so `replicas: 2` starts two concurrent
> `alembic upgrade head` runs against one database. Alembic is not safe to run
> concurrently: both processes read the same current revision and both try to apply the
> same migration, and what that does to a schema depends on the migration. At
> `replicas: 1` it cannot happen. Past that it becomes a Job, plus a wait-for-schema
> initContainer that blocks until `alembic_version` matches the expected head.
>
> This is the kind of thing that looks fine until someone scales up, which is why it is
> written here rather than left to be discovered.

`replicas: 1` is independently required by the rate limiters — both the crash limiter and
the chat limiter are per-process in-memory dicts, so N replicas means N times the
effective limit. Two OCPU does not want two uvicorns anyway. Both constraints have to be
lifted together.

---

## Backups

`41-cronjob-backup.yaml` writes a `pg_dump -Fc` to the `postgres-backups` PVC nightly at
04:30 UTC, keeps 14 days, and prunes only *after* a verified dump has landed — the other
order deletes the last known-good backup and then discovers the database is unreachable.

Each dump is written to a temporary name, parsed with `pg_restore --list`, and only then
renamed into place, so a file truncated by a full disk or an evicted pod never occupies
the slot a good one would have. That catches the failure mode that matters: a truncated
dump is a valid file of the wrong length and looks fine in `ls`.

```
kubectl exec -n kinex deploy/postgres -- ls -la /backups        # not this pod — see below
kubectl create job -n kinex --from=cronjob/kinex-backup backup-now
```

The PVC is mounted only by the backup pod, so listing or copying a dump means running a
pod that mounts it. `postgres-backups` stays `Pending` until the CronJob first runs —
that is `local-path`'s `WaitForFirstConsumer` binding mode working correctly, not a fault.

**These dumps land on the same box's disk as the database they came from.** That covers
the failure this is actually for — a bad migration, a wrong `DELETE`, a corrupted table —
and covers nothing at all if the instance is lost. Getting a copy off the box is separate
work with a separate credential and it is not done.

**A dump nobody has restored is not a backup.** `pg_restore --list` proves the file
parses; it does not prove the data comes back. Restoring one for real is still a manual
step and still the only complete test.

---

## Verifying locally with k3d

The manifests apply unmodified to a laptop k3d cluster, which is how they are checked
without touching Oracle. The default `local-path` storage class exists on both.

```
k3d cluster create kinex --agents 0 -p "8080:80@loadbalancer" -p "8443:443@loadbalancer"
```

The image is the one thing that differs. `20-api.yaml` pins
`ghcr.io/khushhal-mandal/kinex-backend:0.1.0` with `imagePullPolicy: IfNotPresent`, and
until `.github/workflows/backend.yml` has run on a tag that image does not exist in GHCR.
Build it locally and import it into the cluster's containerd, where `IfNotPresent` finds
it:

```
docker build -t ghcr.io/khushhal-mandal/kinex-backend:0.1.0 --target runtime backend/
k3d image import -c kinex ghcr.io/khushhal-mandal/kinex-backend:0.1.0
```

Then apply as above. `31-ollama-pull.yaml` downloads 2.2 GB of models, which takes about
90 seconds on a fast connection and is the long pole.

A Job's pod template is immutable, so editing `31-ollama-pull.yaml` and re-applying fails
with "field is immutable" — `kubectl delete job ollama-pull -n kinex` first. Re-applying
an unchanged completed Job is a no-op and succeeds, which is the right way round: a
one-shot that silently re-ran on every apply would re-download the models.

---

## Not done

- **Nothing is deployed to Oracle.** No instance, no k3s, no DNS record, no certificate.
  Every manifest here has been verified against k3d on a laptop and against nothing else.
- **The image has never been built by CI**, so `0.1.0` exists only where someone imported
  it by hand. `backend.yml` produces it on a `v*` tag push and has not run.
- **No off-box backup copy**, as above.
- **No monitoring.** The root design doc settles this as Uptime Kuma and it is not here.
- **`X-Forwarded-For` is trusted from the pod CIDR only** (`--forwarded-allow-ips` on the
  API's uvicorn command), which is right for Traefik in-cluster. Nothing has verified the
  crash limiter actually meters per real client address behind the ingress rather than
  metering Traefik as one client.
