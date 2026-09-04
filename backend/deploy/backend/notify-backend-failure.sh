#!/bin/sh
set -eu

failed_unit=${1:-runninggu-backend.service}
[ "$failed_unit" = "runninggu-backend.service" ] || { echo "허용되지 않은 unit입니다." >&2; exit 1; }

result=$(systemctl show --property=Result --value "$failed_unit")
status=$(systemctl show --property=ExecMainStatus --value "$failed_unit")
restarts=$(systemctl show --property=NRestarts --value "$failed_unit")
message="런닝구 Spring Boot 실패: unit=$failed_unit result=$result exit_status=$status restarts=$restarts"

logger --tag runninggu-backend-alert -- "$message"

if [ -z "${BACKEND_ALERT_SNS_TOPIC_ARN:-}" ]; then
  echo "BACKEND_ALERT_SNS_TOPIC_ARN이 없어 실패 알림을 보낼 수 없습니다." >&2
  exit 1
fi

aws sns publish \
  --region "${AWS_REGION:-ap-northeast-2}" \
  --topic-arn "$BACKEND_ALERT_SNS_TOPIC_ARN" \
  --message "$message" \
  >/dev/null
