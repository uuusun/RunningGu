#!/bin/sh
set -eu

exec java \
  -cp /opt/graphhopper/graphhopper-web.jar:/opt/runninggu/normalizer \
  ImportConfigNormalizer \
  "$@"
