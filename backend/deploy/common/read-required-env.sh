#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "사용법: $0 <env-file> <KEY>" >&2
  exit 2
fi

env_file=$1
key=$2
[ -r "$env_file" ] || { echo "환경파일을 읽을 수 없습니다: $env_file" >&2; exit 1; }
case "$key" in
  ""|*[!A-Z0-9_]*) echo "환경변수 key 형식이 잘못됐습니다: $key" >&2; exit 1 ;;
esac

# dotenv를 shell로 평가하지 않는다. 정확한 KEY=value 한 줄만 허용한다.
awk -v key="$key" '
  index($0, key "=") == 1 {
    count += 1
    value = substr($0, length(key) + 2)
  }
  END {
    if (count == 0) {
      print key "가 환경파일에 없습니다." > "/dev/stderr"
      exit 1
    }
    if (count > 1) {
      print key "가 환경파일에 중복됐습니다." > "/dev/stderr"
      exit 1
    }
    # Windows 편집기로 저장한 CRLF의 줄 끝만 정규화한다.
    sub(/\r$/, "", value)
    if (length(value) == 0) {
      print key "가 비어 있습니다." > "/dev/stderr"
      exit 1
    }
    first = substr(value, 1, 1)
    last = substr(value, length(value), 1)
    single_quote = sprintf("%c", 39)
    if (first == "\"" || last == "\"" || first == single_quote || last == single_quote) {
      print key " 값은 따옴표 없이 KEY=value 형식으로 작성해야 합니다." > "/dev/stderr"
      exit 1
    }
    print value
  }
' "$env_file"
