"""폐기된 스테이징 경로 없이 운영 배포 계약만 유지하는지 검증한다."""

import json
from pathlib import Path
import unittest


BACKEND = Path(__file__).resolve().parents[2]
REPOSITORY = BACKEND.parent
ARTIFACT_ID = "gh11-korea-20260901-2ff6731b181a-2b8515dd29fc"


class ProductionDeployContractTest(unittest.TestCase):
    def test_release_descriptor_is_production_only(self):
        production = json.loads((BACKEND / "graphhopper/graph-release.production.json").read_text(encoding="utf-8"))

        self.assertEqual(production["environment"], "production")
        self.assertEqual(production["artifactId"], ARTIFACT_ID)
        self.assertFalse((BACKEND / "graphhopper/graph-release.json").exists())

    def test_install_and_verify_require_production_descriptor(self):
        assignment = 'descriptor="$repository_root/backend/graphhopper/graph-release.production.json"'
        for script_name in ("install-graph-artifact.sh", "verify-active-graph.sh"):
            script = (BACKEND / "deploy/graphhopper" / script_name).read_text(encoding="utf-8")
            with self.subTest(script=script_name):
                self.assertIn(assignment, script)
                self.assertIn('"$GRAPHHOPPER_ENVIRONMENT" = production', script)
                self.assertNotIn("staging)", script)
                self.assertIn('--expected-environment "$GRAPHHOPPER_ENVIRONMENT"', script)

    def test_retired_staging_deploy_files_are_absent(self):
        retired = (
            "deploy/env/application.env.example",
            "deploy/env/compose.env.example",
            "deploy/nginx/staging-api.bootstrap.conf",
            "deploy/nginx/staging-api.conf",
            "deploy/nginx/default-reject.conf",
            "graphhopper/graph-release.json",
        )
        for name in retired:
            with self.subTest(file=name):
                self.assertFalse((BACKEND / name).exists())

    def test_production_nginx_files_only_name_public_host(self):
        for name in (
            "production-api.bootstrap.conf",
            "production-root.bootstrap.conf",
            "production-api.conf",
            "default-reject.production.conf",
        ):
            content = (BACKEND / "deploy/nginx" / name).read_text(encoding="utf-8")
            with self.subTest(file=name):
                self.assertNotIn("staging-api.runninggu.store", content)
        public_config = (BACKEND / "deploy/nginx/production-api.conf").read_text(encoding="utf-8")
        self.assertIn("server_name api.runninggu.store;", public_config)
        self.assertIn("server_name runninggu.store;", public_config)
        self.assertIn(
            "access_log /var/log/nginx/runninggu-production.access.log runninggu_minimal;",
            public_config,
        )
        self.assertIn("error_log /dev/null crit;", public_config)
        self.assertNotIn("runninggu_noqs", public_config)

    def test_public_privacy_page_has_explicit_static_routes(self):
        bootstrap = (BACKEND / "deploy/nginx/production-api.bootstrap.conf").read_text(
            encoding="utf-8"
        )
        root_bootstrap = (BACKEND / "deploy/nginx/production-root.bootstrap.conf").read_text(
            encoding="utf-8"
        )
        public_config = (BACKEND / "deploy/nginx/production-api.conf").read_text(
            encoding="utf-8"
        )

        self.assertIn("server_name api.runninggu.store runninggu.store;", bootstrap)
        self.assertIn("server_name runninggu.store;", root_bootstrap)
        self.assertNotIn("listen 443", root_bootstrap)
        self.assertIn("return 404;", root_bootstrap)
        self.assertIn("root /var/www/runninggu-web;", public_config)
        self.assertIn("location = / {", public_config)
        self.assertIn("return 302 /privacy/;", public_config)
        self.assertIn("location = /privacy {", public_config)
        self.assertIn("return 301 /privacy/;", public_config)
        self.assertIn("location ^~ /privacy/ {", public_config)
        self.assertIn("try_files $uri $uri/ =404;", public_config)
        self.assertIn("frame-ancestors 'none'", public_config)

    def test_public_privacy_page_is_release_ready(self):
        privacy = (REPOSITORY / "web/privacy/index.html").read_text(encoding="utf-8")

        for draft_marker in ('name="robots" content="noindex"', 'class="draft"', 'class="todo"', "[확인 필요]"):
            self.assertNotIn(draft_marker, privacy)
        self.assertIn("버전 1.0", privacy)
        self.assertIn("시행일 2026-09-13", privacy)
        self.assertIn("미국 및 Google 데이터센터가 위치한 국가", privacy)
        self.assertIn("요청 경로·질의 문자열·Host·접속 프로그램 정보", privacy)
        self.assertIn("요청 처리 오류 로그", privacy)
        self.assertNotIn("IP 주소·시각·요청 경로 등", privacy)

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
        self.assertIn("repo1-path=/runninggu/production\n", pgbackrest)
        self.assertIn("        ca-certificates \\\n", postgres_image)

    def test_recovery_compose_requires_explicit_backup_repository_path(self):
        recovery = (BACKEND / "compose.recovery.yaml").read_text(encoding="utf-8")

        self.assertIn(
            "PGBACKREST_REPO1_PATH: ${PGBACKREST_REPO1_PATH:?PGBACKREST_REPO1_PATH를 설정해야 합니다}",
            recovery,
        )

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
