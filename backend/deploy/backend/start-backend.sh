#!/bin/sh
set -eu

: "${BACKEND_XMS:?BACKEND_XMS를 설정해야 합니다}"
: "${BACKEND_XMX:?BACKEND_XMX를 설정해야 합니다}"
printf '%s\n' "$BACKEND_XMS" | grep -Eq '^[0-9]+[kKmMgG]$' || {
  echo "BACKEND_XMS 형식이 잘못됐습니다." >&2
  exit 1
}
printf '%s\n' "$BACKEND_XMX" | grep -Eq '^[0-9]+[kKmMgG]$' || {
  echo "BACKEND_XMX 형식이 잘못됐습니다." >&2
  exit 1
}

exec /usr/bin/java \
  "-Xms$BACKEND_XMS" \
  "-Xmx$BACKEND_XMX" \
  -jar /opt/runninggu/current/runninggu-server.jar
