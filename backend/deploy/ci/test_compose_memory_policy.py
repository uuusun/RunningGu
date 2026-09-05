"""실제 Compose 보간 결과의 GraphHopper 전용 swap 정책을 고정한다(계약 §8.1)."""

import copy
import json
import os
from pathlib import Path
import subprocess
import unittest


BACKEND = Path(__file__).resolve().parents[2]


def compose_model(reservation, limit):
    env = dict(os.environ)
    env.update({
        "DB_PASSWORD": "compose-config-check",
        "PGBACKREST_S3_BUCKET": "runninggu-compose-check",
        "PGBACKREST_S3_KMS_KEY_ID": "compose-check",
        "PGBACKREST_S3_ROLE": "runninggu-compose-check",
        "GRAPHHOPPER_SERVER_IMAGE": "runninggu-graphhopper:11.0",
        "GRAPHHOPPER_XMS": "1g",
        "GRAPHHOPPER_XMX": "1g",
        "GRAPHHOPPER_MEMORY_RESERVATION": reservation,
        "GRAPHHOPPER_MEMORY_LIMIT": limit,
    })
    result = subprocess.run(
        ["docker", "compose", "--env-file", "deploy/env/compose.env.example",
         "--profile", "routing", "-f", "compose.yaml", "-f", "compose.ec2.yaml",
         "config", "--format", "json"],
        cwd=BACKEND, env=env, capture_output=True, text=True, encoding="utf-8",
    )
    if result.returncode:
        # 보간된 환경 값과 전체 Compose 모델은 로그에 출력하지 않는다.
        raise AssertionError(f"Compose 모델 생성 실패: exit={result.returncode}")
    return json.loads(result.stdout)["services"]["graphhopper"]


def assert_memory_policy(model, reservation, limit):
    # Compose 버전에 따라 바이트가 JSON 숫자 또는 숫자 문자열로 출력된다.
    assert int(model.get("mem_reservation", 0)) == reservation, "reservation 불일치"
    assert int(model.get("mem_limit", 0)) == limit, "memory 상한 불일치"
    assert int(model.get("memswap_limit", 0)) == limit, "RAM+swap 상한 불일치"
    assert model["restart"] == "no", "Docker 자동 재시작 금지"


class ComposeMemoryPolicyTest(unittest.TestCase):
    def test_baseline_zero_is_unlimited(self):
        assert_memory_policy(compose_model("0", "0"), 0, 0)

    def test_measured_candidate_disables_graphhopper_swap(self):
        assert_memory_policy(compose_model("3g", "4g"), 3221225472, 4294967296)

    def test_limit_change_keeps_swap_limit_tied_to_memory(self):
        assert_memory_policy(compose_model("512m", "2g"), 536870912, 2147483648)

    def test_missing_or_independent_swap_limit_is_rejected(self):
        model = compose_model("3g", "4g")
        for value in (None, 0, 8589934592):
            with self.subTest(swap_limit=value):
                broken = copy.deepcopy(model)
                if value is None:
                    broken.pop("memswap_limit", None)
                else:
                    broken["memswap_limit"] = value
                with self.assertRaisesRegex(AssertionError, "RAM\\+swap"):
                    assert_memory_policy(broken, 3221225472, 4294967296)


if __name__ == "__main__":
    unittest.main()
