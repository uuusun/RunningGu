#!/bin/sh
set -eu

test_root=$(mktemp -d)
cleanup() {
  rm -rf -- "$test_root"
}
trap cleanup EXIT HUP INT TERM

mkdir -p "$test_root/bin" "$test_root/repository/backend" "$test_root/output"

cat > "$test_root/bin/systemctl" <<'EOF'
#!/bin/sh
echo 'ActiveState=active'
echo 'SubState=running'
echo 'NRestarts=0'
echo 'MemoryCurrent=1048576'
echo 'MemoryPeak=2097152'
EOF

cat > "$test_root/bin/docker" <<'EOF'
#!/bin/sh
case "$1" in
  compose) echo 'fixture-container-id' ;;
  inspect) echo 'status=running oom_killed=false restart_count=0' ;;
  stats) echo 'cpu=1.00% memory=100MiB / 8GiB memory_percent=1.22% pids=10' ;;
  *) exit 1 ;;
esac
EOF

cat > "$test_root/bin/git" <<'EOF'
#!/bin/sh
echo '0123456789abcdef'
EOF

chmod 0755 "$test_root/bin/systemctl" "$test_root/bin/docker" "$test_root/bin/git"

PATH="$test_root/bin:$PATH" \
RUNNINGGU_REPOSITORY_ROOT="$test_root/repository" \
  /bin/sh "$(dirname "$0")/collect-runtime-metrics.sh" \
    --duration-seconds 1 \
    --interval-seconds 1 \
    --output "$test_root/output/runtime.log"

grep -q '^schema_version=1$' "$test_root/output/runtime.log"
grep -q '^git_commit=0123456789abcdef$' "$test_root/output/runtime.log"
grep -Eq '^sample sequence=0 .*mem_available_percent=[0-9]+\.[0-9]{3} .*pswpin=[0-9]+ pswpout=[0-9]+$' \
  "$test_root/output/runtime.log"
grep -q '^systemd service=runninggu-backend.service ActiveState=active SubState=running NRestarts=0 MemoryCurrent=1048576 MemoryPeak=2097152$' \
  "$test_root/output/runtime.log"
grep -q '^container service=graphhopper present=true id=fixture-container-id status=running oom_killed=false restart_count=0 cpu=1.00% memory=100MiB / 8GiB memory_percent=1.22% pids=10$' \
  "$test_root/output/runtime.log"
grep -q '^ended_at=.* samples=1$' "$test_root/output/runtime.log"

if /bin/sh "$(dirname "$0")/collect-runtime-metrics.sh" \
  --duration-seconds 1 \
  --output "$test_root/output/runtime.log" >/dev/null 2>&1; then
  echo '기존 출력 파일을 덮어썼습니다.' >&2
  exit 1
fi

echo 'runtime metrics collector 검증 성공'
