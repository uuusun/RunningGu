#!/bin/sh
set -eu

artifact_id=""
env_file="/etc/runninggu/compose.env"
graph_root="${GRAPHHOPPER_GRAPH_ROOT:-/opt/runninggu-data/graph}"
repository_root="${RUNNINGGU_REPOSITORY_ROOT:-/opt/runninggu/repository}"

usage() {
  echo "사용법: $0 --artifact-id <artifact_id> [--env-file <path>]" >&2
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --artifact-id)
      [ "$#" -ge 2 ] || usage
      artifact_id=$2
      shift 2
      ;;
    --env-file)
      [ "$#" -ge 2 ] || usage
      env_file=$2
      shift 2
      ;;
    *)
      usage
      ;;
  esac
done

case "$artifact_id" in
  gh11-korea-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;;
  *) echo "artifact ID 형식이 잘못됐습니다." >&2; exit 1 ;;
esac

[ -r "$env_file" ] || { echo "환경파일을 읽을 수 없습니다: $env_file" >&2; exit 1; }
env_reader="$repository_root/backend/deploy/common/read-required-env.sh"
[ -x "$env_reader" ] || { echo "환경파일 parser를 실행할 수 없습니다: $env_reader" >&2; exit 1; }
GRAPHHOPPER_AWS_REGION=$("$env_reader" "$env_file" GRAPHHOPPER_AWS_REGION)
GRAPHHOPPER_S3_BUCKET=$("$env_reader" "$env_file" GRAPHHOPPER_S3_BUCKET)
GRAPHHOPPER_S3_PREFIX=$("$env_reader" "$env_file" GRAPHHOPPER_S3_PREFIX)
GRAPHHOPPER_ENVIRONMENT=$("$env_reader" "$env_file" GRAPHHOPPER_ENVIRONMENT)

case "$GRAPHHOPPER_ENVIRONMENT" in staging|production) ;; *) echo "GraphHopper 배포 환경이 잘못됐습니다." >&2; exit 1 ;; esac
case "$GRAPHHOPPER_AWS_REGION" in ""|*[!a-z0-9-]*) echo "AWS region 형식이 잘못됐습니다." >&2; exit 1 ;; esac
case "$GRAPHHOPPER_S3_BUCKET" in ""|*..*|*[!a-z0-9.-]*) echo "GraphHopper S3 bucket 형식이 잘못됐습니다." >&2; exit 1 ;; esac
case "$GRAPHHOPPER_S3_PREFIX" in
  ""|/*|*/|*..*|*[!A-Za-z0-9._/-]*) echo "GraphHopper S3 prefix가 안전하지 않습니다." >&2; exit 1 ;;
esac

mkdir -p -- "$graph_root"
graph_root=$(readlink -f -- "$graph_root")
case "$graph_root" in ""|/) echo "graph root가 안전하지 않습니다." >&2; exit 1 ;; esac

descriptor="$repository_root/backend/graphhopper/graph-release.json"
verifier="$repository_root/scripts/osm/import/verify-artifact.sh"
[ -r "$descriptor" ] || { echo "release descriptor가 없습니다: $descriptor" >&2; exit 1; }
[ -x "$verifier" ] || { echo "artifact 검증기를 실행할 수 없습니다: $verifier" >&2; exit 1; }

final_dir="$graph_root/$artifact_id"
if [ -d "$final_dir" ]; then
  "$verifier" \
    --manifest "$final_dir/graph-manifest.json" \
    --graph-dir "$final_dir" \
    --release-descriptor "$descriptor" \
    --expected-artifact-id "$artifact_id" \
    --expected-environment "$GRAPHHOPPER_ENVIRONMENT"
  echo "GraphHopper artifact가 이미 검증된 상태로 설치돼 있습니다: $artifact_id"
  exit 0
fi
[ ! -e "$final_dir" ] || { echo "최종 artifact 경로가 directory가 아닙니다: $final_dir" >&2; exit 1; }

download_dir=$(mktemp -d "$graph_root/.download-$artifact_id.XXXXXX")
staging_dir="$graph_root/.staging-$artifact_id"
[ ! -e "$staging_dir" ] || { echo "이전 staging directory가 남아 있습니다: $staging_dir" >&2; exit 1; }
staging_created=0

cleanup() {
  rm -rf -- "$download_dir"
  if [ "$staging_created" -eq 1 ] && [ -d "$staging_dir" ]; then
    rm -rf -- "$staging_dir"
  fi
}
trap cleanup EXIT HUP INT TERM

s3_base="s3://$GRAPHHOPPER_S3_BUCKET/$GRAPHHOPPER_S3_PREFIX/$GRAPHHOPPER_ENVIRONMENT/$artifact_id"
for file_name in graph-manifest.json SHA256SUMS graph.tar.gz; do
  aws s3 cp \
    --only-show-errors \
    --region "$GRAPHHOPPER_AWS_REGION" \
    "$s3_base/$file_name" \
    "$download_dir/$file_name"
done

"$verifier" \
  --manifest "$download_dir/graph-manifest.json" \
  --checksums "$download_dir/SHA256SUMS" \
  --archive "$download_dir/graph.tar.gz" \
  --release-descriptor "$descriptor" \
  --expected-artifact-id "$artifact_id" \
  --expected-environment "$GRAPHHOPPER_ENVIRONMENT"

mkdir -- "$staging_dir"
staging_created=1
tar \
  --extract \
  --gzip \
  --file "$download_dir/graph.tar.gz" \
  --directory "$staging_dir" \
  --no-same-owner \
  --no-same-permissions

"$verifier" \
  --manifest "$download_dir/graph-manifest.json" \
  --graph-dir "$staging_dir" \
  --release-descriptor "$descriptor" \
  --expected-artifact-id "$artifact_id" \
  --expected-environment "$GRAPHHOPPER_ENVIRONMENT"

chown -R 10001:10001 -- "$staging_dir"
find "$staging_dir" -type d -exec chmod 0755 {} +
find "$staging_dir" -type f -exec chmod 0444 {} +
install -o root -g root -m 0444 \
  "$download_dir/graph-manifest.json" \
  "$staging_dir/graph-manifest.json"

mv -- "$staging_dir" "$final_dir"
staging_created=0
echo "GraphHopper artifact 설치 완료(미활성): $artifact_id"
