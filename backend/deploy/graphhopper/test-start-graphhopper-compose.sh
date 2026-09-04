#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
test_root=$(mktemp -d)
cleanup() { rm -rf -- "$test_root"; }
trap cleanup EXIT HUP INT TERM
mkdir -p "$test_root/bin"

# 실제 /run·Docker·journal을 건드리지 않는 실행 fixture다.
sed "s@^stop_marker=.*@stop_marker=$test_root/stop-requested@" \
  "$script_dir/start-graphhopper-compose.sh" > "$test_root/start.sh"
# fixture가 실행될 때 환경변수를 해석한다.
# shellcheck disable=SC2016
printf '#!/bin/sh\nprintf "fixture error\\n" >&2\nexit "$FIXTURE_EXIT"\n' > "$test_root/bin/docker"
# shellcheck disable=SC2016
printf '#!/bin/sh\nprintf "%%s\\n" "$*" >> "$FIXTURE_LOG"\ncat >> "$FIXTURE_LOG"\n' > "$test_root/bin/logger"
chmod 0755 "$test_root/bin/docker" "$test_root/bin/logger"
export FIXTURE_LOG="$test_root/journal.log"

check_case() {
  requested=$1
  compose_exit=$2
  expected=$3
  : > "$FIXTURE_LOG"
  if [ "$requested" = yes ]; then : > "$test_root/stop-requested"; fi
  actual=0
  PATH="$test_root/bin:$PATH" FIXTURE_EXIT="$compose_exit" \
    /bin/sh "$test_root/start.sh" </dev/null || actual=$?
  if [ "$actual" -ne "$expected" ]; then
    echo "종료 판정 실패: requested=$requested compose=$compose_exit expected=$expected actual=$actual" >&2
    exit 1
  fi
  [ ! -e "$test_root/stop-requested" ]
  if [ "$requested" = yes ]; then
    [ ! -s "$FIXTURE_LOG" ]
  elif [ "$compose_exit" -eq 0 ]; then
    grep -Fq 'exit 0' "$FIXTURE_LOG"
  else
    grep -Fq "exit_code=$compose_exit" "$FIXTURE_LOG"
  fi
}

for code in 0 130 143; do check_case yes "$code" 0; done
for code in 1 137; do check_case yes "$code" "$code"; done
check_case no 0 1
for code in 1 130 137 143; do check_case no "$code" "$code"; done
echo 'GraphHopper stop/비정상 종료 분류 10개 통과'
