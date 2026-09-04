#!/bin/sh
set -eu

graph_root="${GRAPHHOPPER_GRAPH_ROOT:-/opt/runninggu-data/graph}"
repository_root="${RUNNINGGU_REPOSITORY_ROOT:-/opt/runninggu/repository}"
env_file="${RUNNINGGU_COMPOSE_ENV:-/etc/runninggu/compose.env}"

[ -r "$env_file" ] || { echo "환경파일을 읽을 수 없습니다: $env_file" >&2; exit 1; }
env_reader="$repository_root/backend/deploy/common/read-required-env.sh"
[ -x "$env_reader" ] || { echo "환경파일 parser를 실행할 수 없습니다: $env_reader" >&2; exit 1; }
GRAPHHOPPER_ENVIRONMENT=$("$env_reader" "$env_file" GRAPHHOPPER_ENVIRONMENT)
GRAPHHOPPER_SERVER_IMAGE=$("$env_reader" "$env_file" GRAPHHOPPER_SERVER_IMAGE)
case "$GRAPHHOPPER_ENVIRONMENT" in staging|production) ;; *) echo "GraphHopper 배포 환경이 잘못됐습니다." >&2; exit 1 ;; esac
case "$GRAPHHOPPER_SERVER_IMAGE" in
  ""|*[!A-Za-z0-9._/@:+-]*) echo "GraphHopper server image 참조가 안전하지 않습니다." >&2; exit 1 ;;
esac

graph_root=$(readlink -f -- "$graph_root")
if [ -z "$graph_root" ] || [ "$graph_root" = "/" ]; then
  echo "graph root가 안전하지 않습니다." >&2
  exit 1
fi
current="$graph_root/current"
[ -L "$current" ] || { echo "활성 graph current가 상대 symlink가 아닙니다." >&2; exit 1; }
artifact_id=$(readlink -- "$current")
case "$artifact_id" in
  gh11-korea-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;;
  *) echo "current가 단일 artifact ID 상대 symlink가 아닙니다." >&2; exit 1 ;;
esac

active_dir=$(readlink -f -- "$current")
[ "$active_dir" = "$graph_root/$artifact_id" ] || { echo "current가 graph root 밖을 가리킵니다." >&2; exit 1; }
[ -d "$active_dir" ] || { echo "활성 graph directory가 없습니다." >&2; exit 1; }

descriptor="$repository_root/backend/graphhopper/graph-release.json"
verifier="$repository_root/scripts/osm/import/verify-artifact.sh"
"$verifier" \
  --manifest "$active_dir/graph-manifest.json" \
  --graph-dir "$active_dir" \
  --release-descriptor "$descriptor" \
  --expected-artifact-id "$artifact_id" \
  --expected-environment "$GRAPHHOPPER_ENVIRONMENT"

labels=$(docker image inspect \
  --format '{{ index .Config.Labels "org.runninggu.graphhopper.version" }} {{ index .Config.Labels "org.runninggu.graphhopper.jar-sha256" }}' \
  "$GRAPHHOPPER_SERVER_IMAGE")
# 두 label은 공백을 포함할 수 없는 version과 SHA-256이므로 두 필드로 분리한다.
# shellcheck disable=SC2086
set -- $labels
[ "$#" -eq 2 ] || { echo "server image label 출력이 예상 형식이 아닙니다." >&2; exit 1; }
[ "$1" = "11.0" ] || { echo "server image의 GraphHopper version label이 다릅니다." >&2; exit 1; }
[ "$2" = "b59c024afe172ec6ec85b6327006c3138ec58c7d0bcd26253d0e42853f613def" ] || {
  echo "server image의 GraphHopper JAR hash label이 다릅니다." >&2
  exit 1
}

# 실제 server image의 비-root UID가 gh.lock을 만들 directory에 쓸 수 있어야 한다.
docker run \
  --rm \
  --pull never \
  --network none \
  --read-only \
  --entrypoint /bin/sh \
  --mount "type=bind,source=$graph_root,target=/data/graph" \
  "$GRAPHHOPPER_SERVER_IMAGE" \
  -c 'test -w /data/graph/current' || {
    echo "server image 사용자가 활성 graph directory에 gh.lock을 쓸 수 없습니다." >&2
    exit 1
  }

echo "활성 GraphHopper graph 검증 성공: $artifact_id"
