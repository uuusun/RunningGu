#!/bin/sh
set -eu

: "${PBF_FILE_NAME:?PBF_FILE_NAME을 설정해야 합니다}"
: "${PBF_DATE:?PBF_DATE를 설정해야 합니다}"
: "${PBF_SHA256:?PBF_SHA256을 설정해야 합니다}"
: "${BUILDER_IMAGE_DIGEST:?BUILDER_IMAGE_DIGEST를 설정해야 합니다}"
: "${CREATED_BY:?CREATED_BY를 설정해야 합니다}"
: "${IMPORT_XMS:?IMPORT_XMS를 설정해야 합니다}"
: "${IMPORT_XMX:?IMPORT_XMX를 설정해야 합니다}"

case "$PBF_FILE_NAME" in ""|*/*|*\\*|*[!A-Za-z0-9._-]*) echo "PBF 파일명이 안전하지 않습니다." >&2; exit 1 ;; esac
case "$PBF_DATE" in [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;; *) echo "PBF_DATE 형식이 잘못됐습니다." >&2; exit 1 ;; esac
printf '%s\n' "$PBF_SHA256" | grep -Eq '^[0-9a-f]{64}$' || {
  echo "PBF_SHA256 형식이 잘못됐습니다." >&2
  exit 1
}
printf '%s\n' "$IMPORT_XMS" | grep -Eq '^[0-9]+[kKmMgG]$' || {
  echo "IMPORT_XMS 형식이 잘못됐습니다." >&2
  exit 1
}
printf '%s\n' "$IMPORT_XMX" | grep -Eq '^[0-9]+[kKmMgG]$' || {
  echo "IMPORT_XMX 형식이 잘못됐습니다." >&2
  exit 1
}

pbf="/work/input/$PBF_FILE_NAME"
[ -f "$pbf" ] || { echo "PBF 입력을 찾을 수 없습니다: $PBF_FILE_NAME" >&2; exit 1; }
actual_pbf_sha=$(sha256sum "$pbf" | awk '{print $1}')
[ "$actual_pbf_sha" = "$PBF_SHA256" ] || { echo "PBF SHA-256이 고정 입력과 다릅니다." >&2; exit 1; }

[ ! -e /work/output/graph ] || { echo "graph output이 이미 존재합니다. 새 work directory를 사용하십시오." >&2; exit 1; }
mkdir -p /work/output /work/srtm-cache /work/artifacts
sed "s/__PBF_FILE_NAME__/$PBF_FILE_NAME/g" \
  /opt/runninggu/import/graphhopper-import.yml \
  >/work/output/graphhopper-import.generated.yml

java \
  "-Xms$IMPORT_XMS" \
  "-Xmx$IMPORT_XMX" \
  -jar /opt/graphhopper/graphhopper-web.jar \
  import /work/output/graphhopper-import.generated.yml

test -d /work/output/graph
test ! -e /work/output/graph/gh.lock
python3 /opt/runninggu/import/normalize_graph_metadata.py \
  --graph-dir /work/output/graph

/opt/runninggu/import/package-graph.sh \
  --graph-dir /work/output/graph \
  --pbf "$pbf" \
  --pbf-date "$PBF_DATE" \
  --srtm-dir /work/srtm-cache \
  --config /work/output/graphhopper-import.generated.yml \
  --jar /opt/graphhopper/graphhopper-web.jar \
  --builder-image-digest "$BUILDER_IMAGE_DIGEST" \
  --created-by "$CREATED_BY" \
  --output /work/artifacts
