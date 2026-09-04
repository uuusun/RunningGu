"""PR 검증용 출처·checksum 회귀 시험 (artifact 계약 §11.1)."""

import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock


spec = importlib.util.spec_from_file_location("package_pr", Path(__file__).with_name("package-pr-artifact.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
HEAD = "a" * 40
BASE = "b" * 40
MERGE = "c" * 40


class PrArtifactTest(unittest.TestCase):
    def content(self, **changes):
        values = dict(actual_head=HEAD, expected_head=HEAD, run_id="123", attempt="2",
                      pr_number="255", base_commit=BASE, integration_commit=MERGE)
        values.update(changes)
        return module.manifest_bytes(**values)

    def test_manifest_wire_format(self):
        expected = (
            "git_commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n"
            "workflow_run_id=123\nworkflow_run_attempt=2\n"
            "artifact_kind=pr-validation\nallowed_environment=staging\n"
            "pull_request_number=255\nhead_commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n"
            "base_commit=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n"
            "integration_test_commit=cccccccccccccccccccccccccccccccccccccccc\n"
        )
        self.assertEqual(expected.encode(), self.content())

    def test_rejects_merge_commit_labelled_as_head(self):
        with self.assertRaises(ValueError):
            self.content(actual_head=MERGE)

    def test_rejects_invalid_identifiers(self):
        for change in ({"run_id": "0"}, {"attempt": "2\nextra=1"},
                       {"pr_number": "-1"}, {"base_commit": "B" * 40}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.content(**change)

    def test_package_twice_has_identical_bytes_and_real_checksums(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for index, name in enumerate(module.PAYLOADS):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(f"payload-{index}".encode())
            module.package(root, self.content())
            first = {name: (root / name).read_bytes() for name in (module.MANIFEST, "SHA256SUMS")}
            module.package(root, self.content())
            self.assertEqual(first, {name: (root / name).read_bytes() for name in first})
            for line in (root / "SHA256SUMS").read_text().splitlines():
                digest, name = line.split("  ")
                self.assertEqual(hashlib.sha256((root / name).read_bytes()).hexdigest(), digest)

    def test_missing_payload_fails_before_manifest_write(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaises(ValueError):
                module.package(root, self.content())
            self.assertFalse((root / module.MANIFEST).exists())

    def test_symlink_payload_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.object(Path, "is_symlink", lambda path: path.name == "runninggu-server.jar"):
                with self.assertRaisesRegex(ValueError, "symlink"):
                    module.package(Path(tmp), self.content())

    def test_unexpected_file_is_not_uploaded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in module.PAYLOADS:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"fixture")
            (root / "unexpected.env").write_bytes(b"fixture")
            with self.assertRaisesRegex(ValueError, "허용하지 않은"):
                module.package(root, self.content())
            self.assertFalse((root / module.MANIFEST).exists())


if __name__ == "__main__":
    unittest.main()
