# syntax=docker/dockerfile:1

# Python 3.12 is locked in the root design doc. Pinned to a Debian release rather than bare
# `slim` so a rebuild is not also a base-OS change.
FROM python:3.12-slim-bookworm

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /srv

COPY pyproject.toml alembic.ini ./
COPY app ./app

# Editable, so /srv/app is the only copy of the source in the image. A non-editable
# install would leave one copy in site-packages for `import app` and another at /srv for
# alembic to read revision files from, and the two would drift the moment anyone edited
# the wrong one. The pip cache mount keeps a rebuild off the network.
RUN --mount=type=cache,target=/root/.cache/pip pip install -e .

RUN useradd --system --create-home --uid 10001 kinex
USER kinex

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
