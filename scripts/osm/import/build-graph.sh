#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH='' cd -- "$script_dir/../../.." && pwd)

pbf=""
pbf_date=""
pbf_sha256=""
work_dir=""
created_by=""
import_xms="1g"
import_xmx="8g"
builder_image="runninggu-graphhopper-builder:11.0"

usage() {
  echo "사용법: $0 --pbf <날짜고정.osm.pbf> --pbf-date <YYYY-MM-DD> --pbf-sha256 <sha256> --work-dir <빈_작업경로> --created-by <팀식별자> [--import-xms 1g] [--import-xmx 8g]" >&2
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --pbf) [ "$#" -ge 2 ] || usage; pbf=$2; shift 2 ;;
    --pbf-date) [ "$#" -ge 2 ] || usage; pbf_date=$2; shift 2 ;;
    --pbf-sha256) [ "$#" -ge 2 ] || usage; pbf_sha256=$2; shift 2 ;;
    --work-dir) [ "$#" -ge 2 ] || usage; work_dir=$2; shift 2 ;;
    --created-by) [ "$#" -ge 2 ] || usage; created_by=$2; shift 2 ;;
    --import-xms) [ "$#" -ge 2 ] || usage; import_xms=$2; shift 2 ;;
    --import-xmx) [ "$#" -ge 2 ] || usage; import_xmx=$2; shift 2 ;;
    *) usage ;;
  esac
done

if [ -z "$pbf" ] || [ -z "$pbf_date" ] || [ -z "$pbf_sha256" ] || [ -z "$work_dir" ] || [ -z "$created_by" ]; then
  usage
fi
[ -f "$pbf" ] || { echo "PBF 파일이 없습니다: $pbf" >&2; exit 1; }

pbf_dir=$(CDPATH='' cd -- "$(dirname -- "$pbf")" && pwd)
pbf_file=$(basename -- "$pbf")
work_parent=$(dirname -- "$work_dir")
mkdir -p -- "$work_parent"
work_parent=$(CDPATH='' cd -- "$work_parent" && pwd)
work_dir="$work_parent/$(basename -- "$work_dir")"
[ ! -e "$work_dir" ] || { echo "work directory가 이미 존재합니다: $work_dir" >&2; exit 1; }
mkdir -p -- "$work_dir/output" "$work_dir/srtm-cache" "$work_dir/artifacts"

docker buildx build \
  --load \
  --platform linux/amd64 \
  --provenance=false \
  --sbom=false \
  --build-arg SOURCE_DATE_EPOCH=0 \
  --tag "$builder_image" \
  --file "$script_dir/Dockerfile" \
  "$repository_root"

builder_digest=$(docker image inspect --format '{{.Id}}' "$builder_image")
case "$builder_digest" in sha256:*) ;; *) echo "builder image digest를 얻지 못했습니다." >&2; exit 1 ;; esac

docker run --rm \
  --platform linux/amd64 \
  --user "$(id -u):$(id -g)" \
  --env HOME=/tmp \
  --env "PBF_FILE_NAME=$pbf_file" \
  --env "PBF_DATE=$pbf_date" \
  --env "PBF_SHA256=$pbf_sha256" \
  --env "BUILDER_IMAGE_DIGEST=$builder_digest" \
  --env "CREATED_BY=$created_by" \
  --env "IMPORT_XMS=$import_xms" \
  --env "IMPORT_XMX=$import_xmx" \
  --mount "type=bind,source=$pbf_dir,target=/work/input,readonly" \
  --mount "type=bind,source=$work_dir/output,target=/work/output" \
  --mount "type=bind,source=$work_dir/srtm-cache,target=/work/srtm-cache" \
  --mount "type=bind,source=$work_dir/artifacts,target=/work/artifacts" \
  "$builder_image"

echo "builder_image_digest=$builder_digest"
echo "artifact_root=$work_dir/artifacts"
