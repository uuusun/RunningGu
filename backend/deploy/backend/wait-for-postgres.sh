#!/bin/sh
set -eu

cd /opt/runninggu/repository/backend

docker compose \
  --env-file /etc/runninggu/compose.env \
  -f compose.yaml \
  -f compose.ec2.yaml \
  config --quiet

attempt=1
while [ "$attempt" -le 30 ]; do
  # PostgreSQL 변수는 호스트가 아니라 컨테이너 내부 shell에서 확장한다.
  # shellcheck disable=SC2016
  if timeout 3s docker compose \
    --env-file /etc/runninggu/compose.env \
    -f compose.yaml \
    -f compose.ec2.yaml \
    exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    >/dev/null 2>&1
  then
    exit 0
  fi
  if [ "$attempt" -lt 30 ]; then
    sleep 1
  fi
  attempt=$((attempt + 1))
done

echo "PostgreSQL이 2분 안에 준비되지 않았습니다." >&2
exit 1
