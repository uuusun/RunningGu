"""실제 EC2 배포 예시의 4GiB heap·상한·swap 정책을 검증한다(계약 §8.1)."""

import configparser
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


BACKEND = Path(__file__).resolve().parents[2]
COMPOSE_ENV = BACKEND / "deploy/env/compose.env.example"
MEMORY_KEYS = (
    "GRAPHHOPPER_XMS", "GRAPHHOPPER_XMX",
    "GRAPHHOPPER_MEMORY_RESERVATION", "GRAPHHOPPER_MEMORY_LIMIT",
)


def compose_result(overrides=None, env_file=COMPOSE_ENV):
    env = dict(os.environ)
    # PC·CI의 환경변수가 저장소 배포 예시의 결함을 가리지 않게 한다.
    for key in MEMORY_KEYS:
        env.pop(key, None)
    env.update({
        "DB_PASSWORD": "compose-config-check",
        "PGBACKREST_S3_BUCKET": "runninggu-compose-check",
        "PGBACKREST_S3_KMS_KEY_ID": "compose-check",
        "PGBACKREST_S3_ROLE": "runninggu-compose-check",
        "GRAPHHOPPER_SERVER_IMAGE": "runninggu-graphhopper:11.0",
    })
    env.update(overrides or {})
    return subprocess.run(
        ["docker", "compose", "--env-file", str(env_file),
         "--profile", "routing", "-f", "compose.yaml", "-f", "compose.ec2.yaml",
         "config", "--format", "json"],
        cwd=BACKEND, env=env, capture_output=True, text=True, encoding="utf-8",
    )


def compose_model(reservation=None, limit=None):
    overrides = {}
    if reservation is not None:
        overrides["GRAPHHOPPER_MEMORY_RESERVATION"] = reservation
    if limit is not None:
        overrides["GRAPHHOPPER_MEMORY_LIMIT"] = limit
    result = compose_result(overrides)
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


def backend_profile():
    env = {}
    for line in (BACKEND / "deploy/env/application.env.example").read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            env[key] = value
    unit = configparser.ConfigParser(interpolation=None)
    unit.optionxform = str
    unit.read(BACKEND / "deploy/systemd/runninggu-backend.service", encoding="utf-8")
    return env, dict(unit["Service"])


def assert_4g_profile(model, env, service):
    assert_memory_policy(model, 2147483648, 2684354560)
    command = model["command"]
    assert [arg for arg in command if arg.startswith("-Xms")] == ["-Xms512m"], "GH 초기 heap 불일치"
    assert [arg for arg in command if arg.startswith("-Xmx")] == ["-Xmx2g"], "GH 최대 heap 불일치"
    assert env["BACKEND_XMS"] == "256m", "backend 초기 heap 불일치"
    assert env["BACKEND_XMX"] == "512m", "backend 최대 heap 불일치"
    assert service["MemoryHigh"] == "640M", "backend MemoryHigh 불일치"
    assert service["MemoryMax"] == "768M", "backend MemoryMax 불일치"


class ComposeMemoryPolicyTest(unittest.TestCase):
    def test_repository_examples_render_approved_4g_profile(self):
        assert_4g_profile(compose_model(), *backend_profile())

    def test_empty_memory_values_fail_before_deployment(self):
        for key in MEMORY_KEYS:
            with self.subTest(key=key):
                result = compose_result({key: ""})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(key, result.stderr)

    def test_missing_memory_values_fail_before_deployment(self):
        lines = COMPOSE_ENV.read_text(encoding="utf-8").splitlines()
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "compose.env"
            for key in MEMORY_KEYS:
                with self.subTest(key=key):
                    env_file.write_text("\n".join(line for line in lines if not line.startswith(key + "=")), encoding="utf-8")
                    result = compose_result(env_file=env_file)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(key, result.stderr)

    def test_4g_profile_rejects_graphhopper_heap_drift(self):
        model = compose_model()
        model["command"] = ["-Xmx4g" if arg == "-Xmx2g" else arg for arg in model["command"]]
        with self.assertRaisesRegex(AssertionError, "GH 최대 heap"):
            assert_4g_profile(model, *backend_profile())

    def test_4g_profile_rejects_backend_heap_and_unit_drift(self):
        model = compose_model()
        for key, value in (("BACKEND_XMS", "1g"), ("BACKEND_XMX", "2g"),
                           ("MemoryHigh", "infinity"), ("MemoryMax", "infinity")):
            with self.subTest(key=key):
                env, service = backend_profile()
                (env if key.startswith("BACKEND_") else service)[key] = value
                with self.assertRaisesRegex(AssertionError, "backend"):
                    assert_4g_profile(model, env, service)

    def test_baseline_zero_is_unlimited(self):
        # 과거 무제한 측정을 명시적으로 재현할 수 있지만 현재 배포 예시는 아니다.
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
