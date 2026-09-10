"""운영 배포 파일이 스테이징과 분리된 계약을 유지하는지 검증한다."""

import json
from pathlib import Path
import unittest


BACKEND = Path(__file__).resolve().parents[2]
REPOSITORY = BACKEND.parent
ARTIFACT_ID = "gh11-korea-20260901-2ff6731b181a-2b8515dd29fc"


class ProductionDeployContractTest(unittest.TestCase):
    def test_release_descriptors_pin_same_payload_for_separate_environments(self):
        staging = json.loads((BACKEND / "graphhopper/graph-release.json").read_text(encoding="utf-8"))
        production = json.loads((BACKEND / "graphhopper/graph-release.production.json").read_text(encoding="utf-8"))

        self.assertEqual(staging["environment"], "staging")
        self.assertEqual(production["environment"], "production")
        self.assertEqual(staging["artifactId"], ARTIFACT_ID)
        for key in ("artifactId", "manifestSha256", "buildInputSha256"):
            self.assertEqual(staging[key], production[key], key)

    def test_install_and_verify_select_environment_descriptor(self):
        expected = {
            "staging": 'descriptor="$repository_root/backend/graphhopper/graph-release.json"',
            "production": 'descriptor="$repository_root/backend/graphhopper/graph-release.production.json"',
        }
        for script_name in ("install-graph-artifact.sh", "verify-active-graph.sh"):
            script = (BACKEND / "deploy/graphhopper" / script_name).read_text(encoding="utf-8")
            with self.subTest(script=script_name):
                for environment, assignment in expected.items():
                    self.assertIn(f"{environment}) {assignment}", script)
                self.assertIn('--expected-environment "$GRAPHHOPPER_ENVIRONMENT"', script)

    def test_production_nginx_files_only_name_public_host(self):
        for name in (
            "production-api.bootstrap.conf",
            "production-api.conf",
            "default-reject.production.conf",
        ):
            content = (BACKEND / "deploy/nginx" / name).read_text(encoding="utf-8")
            with self.subTest(file=name):
                self.assertNotIn("staging-api.runninggu.store", content)
        public_config = (BACKEND / "deploy/nginx/production-api.conf").read_text(encoding="utf-8")
        self.assertIn("server_name api.runninggu.store;", public_config)
        log_format = public_config.split("server {", 1)[0]
        self.assertIn("$request_method $uri $server_protocol", log_format)
        self.assertNotIn("$request_uri", log_format)

    def test_no_example_contains_secret_values(self):
        for name in ("application.production.env.example", "compose.production.env.example"):
            content = (BACKEND / "deploy/env" / name).read_text(encoding="utf-8")
            with self.subTest(file=name):
                for key in ("DB_PASSWORD", "JWT_SECRET", "KTO_SERVICE_KEY", "KAKAO_REST_KEY", "SMTP_PASSWORD"):
                    if key in content:
                        self.assertIn(f"{key}=\n", content + "\n")

    def test_pgbackrest_connects_with_the_deployment_database_role(self):
        compose_env = (BACKEND / "deploy/env/compose.production.env.example").read_text(encoding="utf-8")
        pgbackrest = (BACKEND / "deploy/pgbackrest/pgbackrest.conf").read_text(encoding="utf-8")
        postgres_image = (BACKEND / "postgres/Dockerfile").read_text(encoding="utf-8")

        self.assertIn("DB_USERNAME=runninggu\n", compose_env)
        self.assertIn("pg1-user=runninggu\n", pgbackrest)
        self.assertIn("        ca-certificates \\\n", postgres_image)

    def test_runtime_iam_policy_is_scoped_to_production_paths(self):
        policy = json.loads(
            (BACKEND / "deploy/aws/runninggu-production-runtime-access.json").read_text(encoding="utf-8")
        )
        serialized = json.dumps(policy, sort_keys=True)
        self.assertIn("graphhopper/production/*", serialized)
        self.assertIn("runninggu/production/*", serialized)
        self.assertIn("parameter/runninggu/production/*", serialized)
        self.assertIn("runninggu-production-alerts", serialized)
        self.assertNotIn("graphhopper/staging/*", serialized)
        self.assertNotIn("runninggu/staging/*", serialized)
        self.assertNotIn("parameter/runninggu/staging/*", serialized)
        self.assertNotIn('"Action": "s3:*"', serialized)
        self.assertNotIn('"Action": "ssm:*"', serialized)


if __name__ == "__main__":
    unittest.main()
