#!/bin/sh
set -eu

compose_error=$(mktemp /tmp/runninggu-graphhopper-compose.XXXXXX)
cleanup() {
  # trap에서 간접 호출되는 함수다.
  # shellcheck disable=SC2317
  rm -f -- "$compose_error"
}
trap cleanup EXIT HUP INT TERM

set +e
docker compose \
  --env-file /etc/runninggu/compose.env \
  --profile routing \
  -f /opt/runninggu/repository/backend/compose.yaml \
  -f /opt/runninggu/repository/backend/compose.ec2.yaml \
  up \
  --no-deps \
  --no-build \
  --abort-on-container-exit \
  --exit-code-from graphhopper \
  graphhopper \
  2>"$compose_error"
exit_code=$?
set -e

stop_marker=/run/runninggu-graphhopper/stop-requested
if [ -f "$stop_marker" ]; then
  rm -f -- "$stop_marker"
elif [ "$exit_code" -eq 0 ]; then
  logger --tag runninggu-graphhopper -- "GraphHopper Compose가 예상하지 않게 exit 0으로 종료됐습니다."
  exit_code=1
else
  logger --tag runninggu-graphhopper -- "GraphHopper Compose 실패: exit_code=$exit_code"
  tail -n 40 "$compose_error" \
    | sed -E \
        -e 's/(password|passwd|token|secret|access[_-]?key|session[_-]?key)=([^[:space:]]+)/\1=[REDACTED]/Ig' \
        -e 's/AKIA[0-9A-Z]{16}/[REDACTED]/g' \
    | logger --tag runninggu-graphhopper
fi

exit "$exit_code"
