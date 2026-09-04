#!/bin/sh
set -eu

duration_seconds=""
interval_seconds=5
output=""
repository_root=${RUNNINGGU_REPOSITORY_ROOT:-/opt/runninggu/repository}

usage() {
  echo "사용법: $0 --duration-seconds <초> --output <새_로그_경로> [--interval-seconds 5]" >&2
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --duration-seconds) [ "$#" -ge 2 ] || usage; duration_seconds=$2; shift 2 ;;
    --interval-seconds) [ "$#" -ge 2 ] || usage; interval_seconds=$2; shift 2 ;;
    --output) [ "$#" -ge 2 ] || usage; output=$2; shift 2 ;;
    *) usage ;;
  esac
done

case "$duration_seconds" in ''|*[!0-9]*|0) usage ;; esac
case "$interval_seconds" in ''|*[!0-9]*|0) usage ;; esac
[ -n "$output" ] || usage
[ ! -e "$output" ] || { echo "출력 파일이 이미 있습니다: $output" >&2; exit 1; }

output_parent=$(dirname -- "$output")
mkdir -p -- "$output_parent"
umask 0027
: > "$output"

compose_env=/etc/runninggu/compose.env
backend_dir="$repository_root/backend"
start_epoch=$(date +%s)
end_epoch=$((start_epoch + duration_seconds))
sequence=0

write_line() {
  printf '%s\n' "$*" >> "$output"
}

container_sample() {
  service_name=$1
  container_id=$(docker compose \
    --env-file "$compose_env" \
    --profile routing \
    -f "$backend_dir/compose.yaml" \
    -f "$backend_dir/compose.ec2.yaml" \
    ps -q "$service_name" 2>/dev/null || true)
  if [ -z "$container_id" ]; then
    write_line "container service=$service_name present=false"
    return
  fi
  state=$(docker inspect \
    --format 'status={{.State.Status}} oom_killed={{.State.OOMKilled}} restart_count={{.RestartCount}}' \
    "$container_id")
  stats=$(docker stats --no-stream \
    --format 'cpu={{.CPUPerc}} memory={{.MemUsage}} memory_percent={{.MemPerc}} pids={{.PIDs}}' \
    "$container_id")
  write_line "container service=$service_name present=true id=$container_id $state $stats"
}

write_line "schema_version=1"
write_line "started_at=$(date -Iseconds)"
write_line "duration_seconds=$duration_seconds interval_seconds=$interval_seconds"
write_line "git_commit=$(git -C "$repository_root" rev-parse HEAD 2>/dev/null || echo unknown)"

while [ "$(date +%s)" -lt "$end_epoch" ]; do
  now_epoch=$(date +%s)
  mem_values=$(awk '
    /^MemTotal:/ { total=$2 }
    /^MemAvailable:/ { available=$2 }
    /^SwapTotal:/ { swap_total=$2 }
    /^SwapFree:/ { swap_free=$2 }
    END {
      pct=(total > 0 ? available * 100 / total : 0)
      printf "mem_total_kib=%d mem_available_kib=%d mem_available_percent=%.3f swap_total_kib=%d swap_used_kib=%d", total, available, pct, swap_total, swap_total-swap_free
    }
  ' /proc/meminfo)
  swap_counters=$(awk '
    /^pswpin / { in_count=$2 }
    /^pswpout / { out_count=$2 }
    END { printf "pswpin=%d pswpout=%d", in_count, out_count }
  ' /proc/vmstat)

  write_line "sample sequence=$sequence at=$(date -Iseconds) epoch=$now_epoch $mem_values $swap_counters"
  {
    systemctl show runninggu-backend.service \
      -p ActiveState -p SubState -p NRestarts -p MemoryCurrent -p MemoryPeak \
      --no-pager \
      | tr '\n' ' ' \
      | sed 's/[[:space:]]*$//' \
      | sed 's/^/systemd service=runninggu-backend.service /'
    printf '\n'
    systemctl show runninggu-graphhopper.service \
      -p ActiveState -p SubState -p NRestarts \
      --no-pager \
      | tr '\n' ' ' \
      | sed 's/[[:space:]]*$//' \
      | sed 's/^/systemd service=runninggu-graphhopper.service /'
    printf '\n'
  } >> "$output"
  container_sample graphhopper
  container_sample postgres
  write_line "sample_end sequence=$sequence"

  sequence=$((sequence + 1))
  next_epoch=$((start_epoch + sequence * interval_seconds))
  sleep_seconds=$((next_epoch - $(date +%s)))
  if [ "$sleep_seconds" -gt 0 ]; then
    sleep "$sleep_seconds"
  fi
done

write_line "ended_at=$(date -Iseconds) samples=$sequence"
echo "runtime metrics 저장 완료: $output"
