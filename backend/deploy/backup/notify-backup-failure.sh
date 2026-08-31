#!/bin/sh
set -eu

failed_unit="${1:-runninggu-postgres-backup.service}"
message="런닝구 PostgreSQL 백업 실패: unit=${failed_unit}"

logger --tag runninggu-backup-alert -- "$message"

if [ -z "${BACKUP_ALERT_SNS_TOPIC_ARN:-}" ]; then
  echo "BACKUP_ALERT_SNS_TOPIC_ARN이 없어 실패 알림을 보낼 수 없습니다." >&2
  exit 1
fi

aws sns publish \
  --region "${AWS_REGION:-ap-northeast-2}" \
  --topic-arn "$BACKUP_ALERT_SNS_TOPIC_ARN" \
  --message "$message" \
  >/dev/null
