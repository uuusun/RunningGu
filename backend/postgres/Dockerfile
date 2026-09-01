FROM postgres:17.10-trixie@sha256:7958605b474b3d264a969cb3a123d6aa00ad1e1fe9da8a69984dabb704d93317

ARG PGBACKREST_VERSION=2.55.1-1

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        "pgbackrest=${PGBACKREST_VERSION}" \
    && pgbackrest version \
    && install -d -o postgres -g postgres -m 0750 \
        /var/lib/pgbackrest \
        /var/log/pgbackrest \
        /var/spool/pgbackrest \
    && rm -rf /var/lib/apt/lists/*
