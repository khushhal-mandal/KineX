"""Scheduled batch work. Separate processes, not background tasks in the API.

A nightly job that shares the API's event loop would compete with request handling for
the two cores this box has, and an OOM in a summary run would take the API down with it.
These run as their own container — `docker compose run` now, a k3s CronJob in Phase 7.
"""
