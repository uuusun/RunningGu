#!/bin/sh
set -eu

stop_marker=/run/runninggu-graphhopper/stop-requested
: >"$stop_marker"

if ! docker compose \
  --env-file /etc/runninggu/compose.env \
  --profile routing \
  -f /opt/runninggu/repository/backend/compose.yaml \
  -f /opt/runninggu/repository/backend/compose.ec2.yaml \
  stop graphhopper
then
  rm -f -- "$stop_marker"
  exit 1
fi
