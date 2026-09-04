#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
parser="$script_dir/read-required-env.sh"
temporary=$(mktemp -d)

cleanup() {
  rm -rf -- "$temporary"
}
trap cleanup EXIT HUP INT TERM

assert_failure() {
  env_file=$1
  key=$2
  expected=$3
  if output=$("$parser" "$env_file" "$key" 2>&1); then
    echo "실패해야 하는 env 입력이 통과했습니다: key=$key" >&2
    exit 1
  fi
  printf '%s' "$output" | grep -Fq -- "$expected" || {
    echo "env parser 오류 메시지가 다릅니다: $output" >&2
    exit 1
  }
}

printf 'GRAPHHOPPER_ENVIRONMENT=staging\r\nTOKEN=left=middle=right\r\n' >"$temporary/valid.env"
[ "$("$parser" "$temporary/valid.env" GRAPHHOPPER_ENVIRONMENT)" = "staging" ]
[ "$("$parser" "$temporary/valid.env" TOKEN)" = "left=middle=right" ]

printf 'KEY_EXTRA=value\n' >"$temporary/prefix.env"
assert_failure "$temporary/prefix.env" KEY "환경파일에 없습니다"

printf 'KEY=one\nKEY=two\n' >"$temporary/duplicate.env"
assert_failure "$temporary/duplicate.env" KEY "중복됐습니다"

printf 'KEY=\r\n' >"$temporary/empty.env"
assert_failure "$temporary/empty.env" KEY "비어 있습니다"

printf 'IMAGE="runninggu-graphhopper:11.0"\n' >"$temporary/double-quoted.env"
assert_failure "$temporary/double-quoted.env" IMAGE "따옴표 없이"

printf "IMAGE='runninggu-graphhopper:11.0'\n" >"$temporary/single-quoted.env"
assert_failure "$temporary/single-quoted.env" IMAGE "따옴표 없이"

echo "env parser 회귀 테스트 성공"
