#!/bin/sh
set -eu

compose() {
  docker compose \
    --env-file /etc/runninggu/compose.env \
    -f compose.yaml \
    -f compose.ec2.yaml \
    "$@"
}

compose config --quiet

archive_state="$(compose exec -T postgres sh -eu -c '
  psql --no-psqlrc --tuples-only --no-align \
    -U "$POSTGRES_USER" \
    -d "$POSTGRES_DB" \
    -c "
      SELECT CASE
        WHEN last_failed_time IS NOT NULL
          AND (last_archived_time IS NULL OR last_failed_time >= last_archived_time)
        THEN '\''failed'\''
        ELSE '\''ok'\''
      END
      FROM pg_stat_archiver
    "
')"

if [ "$archive_state" != "ok" ]; then
  echo "PostgreSQL WAL archive의 마지막 결과가 실패 상태입니다." >&2
  exit 1
fi

if ! compose exec -T --user postgres postgres sh -eu -c '
  test -z "$(find "$PGDATA/pg_wal/archive_status" \
    -type f -name "*.ready" -mmin +10 -print -quit)"
'; then
  echo "10분 넘게 archive 대기 중인 WAL segment가 있습니다." >&2
  exit 1
fi

echo "PostgreSQL WAL archive 감시 정상"
