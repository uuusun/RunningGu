#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import artifact_contract as contract


class ArtifactContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.graph = self.root / "graph"
        (self.graph / "nested").mkdir(parents=True)
        (self.graph / "edges").write_bytes(b"edge-data")
        (self.graph / "nested" / "nodes").write_bytes(b"node-data")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def make_bundle(self) -> tuple[Path, Path, Path, Path, str]:
        archive = self.root / contract.ARCHIVE_NAME
        contract.create_deterministic_archive(self.graph, archive)
        graph_sha = contract.hash_tree(self.graph, contract.IGNORED_GRAPH_FILES)
        import_sha = "1" * 64
        pbf_sha = "2" * 64
        srtm_sha = "3" * 64
        build_input = contract.build_input_sha256(
            contract.GRAPHHOPPER_JAR_SHA256,
            import_sha,
            pbf_sha,
            srtm_sha,
        )
        artifact_id = f"gh11-korea-20260901-{build_input[:12]}-{graph_sha[:12]}"
        manifest = {
            "schemaVersion": 1,
            "artifactId": artifact_id,
            "buildInputSha256": build_input,
            "graphhopper": {
                "version": "11.0",
                "jarSha256": contract.GRAPHHOPPER_JAR_SHA256,
                "builderImageDigest": "sha256:" + "4" * 64,
            },
            "source": {
                "pbfFileName": "south-korea-2026-09-01.osm.pbf",
                "pbfDate": "2026-09-01",
                "pbfSha256": pbf_sha,
                "srtmProvider": "srtm",
                "srtmFilesSha256": srtm_sha,
            },
            "importConfig": {
                "normalizedSha256": import_sha,
                "profiles": ["run"],
                "landmarkProfiles": ["run"],
            },
            "graph": {
                "filesSha256": graph_sha,
                "createdAt": "2026-09-01T00:00:00Z",
                "createdBy": "test-builder",
            },
            "archive": {
                "fileName": contract.ARCHIVE_NAME,
                "sha256": contract.sha256_file(archive),
                "sizeBytes": archive.stat().st_size,
            },
        }
        manifest_path = self.root / contract.MANIFEST_NAME
        contract.write_json(manifest_path, manifest)
        checksums = self.root / contract.CHECKSUMS_NAME
        checksums.write_text(
            f"{contract.sha256_file(archive)}  {contract.ARCHIVE_NAME}\n"
            f"{contract.sha256_file(manifest_path)}  {contract.MANIFEST_NAME}\n",
            encoding="utf-8",
        )
        descriptor = self.root / "graph-release.json"
        contract.write_json(
            descriptor,
            {
                "schemaVersion": 1,
                "environment": "staging",
                "artifactId": artifact_id,
                "manifestSha256": contract.sha256_file(manifest_path),
                "buildInputSha256": build_input,
            },
        )
        return manifest_path, checksums, archive, descriptor, artifact_id

    @staticmethod
    def normalized_import_config() -> dict[str, object]:
        return {
            "datareader.file": "south-korea-2026-09-01.osm.pbf",
            "import.osm.ignored_highways": "motorway,trunk",
            "graph.encoded_values": (
                "foot_access, foot_priority, foot_average_speed, average_slope, hike_rating, "
                "road_class, road_environment, surface"
            ),
            "graph.elevation.provider": "srtm",
            "graph.elevation.cache_dir": "$SRTM_CACHE",
            "profiles": [{"name": "run", "custom_model": {"priority": []}}],
            "profiles_ch": [],
            "profiles_lm": [{"profile": "run"}],
        }

    def test_valid_bundle_and_active_tree(self) -> None:
        manifest, checksums, archive, descriptor, artifact_id = self.make_bundle()
        (self.graph / contract.MANIFEST_NAME).write_bytes(manifest.read_bytes())
        (self.graph / "gh.lock").write_text("runtime", encoding="utf-8")

        contract.verify_bundle(
            manifest,
            checksums,
            archive,
            self.graph,
            descriptor,
            artifact_id,
            "staging",
        )

    def test_unexpected_active_file_fails(self) -> None:
        manifest, checksums, archive, descriptor, artifact_id = self.make_bundle()
        (self.graph / "unexpected").write_text("tampered", encoding="utf-8")

        with self.assertRaisesRegex(contract.ContractError, "graph tree hash"):
            contract.verify_bundle(
                manifest,
                checksums,
                archive,
                self.graph,
                descriptor,
                artifact_id,
                "staging",
            )

    def test_archive_is_deterministic(self) -> None:
        first = self.root / "first.tar.gz"
        second = self.root / "second.tar.gz"
        contract.create_deterministic_archive(self.graph, first)
        contract.create_deterministic_archive(self.graph, second)

        self.assertEqual(contract.sha256_file(first), contract.sha256_file(second))

    def test_build_input_wire_format_golden(self) -> None:
        self.assertEqual(
            contract.build_input_sha256(
                contract.GRAPHHOPPER_JAR_SHA256,
                "1" * 64,
                "2" * 64,
                "3" * 64,
            ),
            "53685974513b7c3670c7670f175d529b192a5aa2ecb38ed6e665339e434ca578",
        )

    def test_graph_tree_wire_format_golden(self) -> None:
        digest, lines = contract.canonical_file_lines(
            contract.tree_file_records(self.graph, set())
        )

        self.assertEqual(
            lines,
            [
                "281c9d7f84f6a2dfb37b0345722584561884f3fcf398b0d6e843c04eb2ef8bc9 9 edges\n",
                "d7f0e8a8d84f43b0983187b3b6904326c893a3d9fcd31c7ba6899ed5b89553b0 9 nested/nodes\n",
            ],
        )
        self.assertEqual(
            digest,
            "920de8fd4cd30a9028a71ed4b26959a8d82672798eb9e0f0ecddfbabc6fe46c2",
        )

    def test_archive_traversal_fails(self) -> None:
        unsafe = self.root / "unsafe.tar.gz"
        payload = self.root / "payload"
        payload.write_text("unsafe", encoding="utf-8")
        with tarfile.open(unsafe, "w:gz") as archive:
            archive.add(payload, arcname="../outside")

        with self.assertRaisesRegex(contract.ContractError, "POSIX 상대경로"):
            contract.archive_file_records(unsafe)

    def test_release_descriptor_manifest_hash_mismatch_fails(self) -> None:
        manifest, checksums, archive, descriptor, artifact_id = self.make_bundle()
        value = json.loads(descriptor.read_text(encoding="utf-8"))
        value["manifestSha256"] = "f" * 64
        contract.write_json(descriptor, value)

        with self.assertRaisesRegex(contract.ContractError, "manifest SHA-256"):
            contract.verify_bundle(
                manifest,
                checksums,
                archive,
                None,
                descriptor,
                artifact_id,
                "staging",
            )

    def test_normalized_import_config_requires_run_lm_and_srtm(self) -> None:
        contract.validate_normalized_import_config(
            self.normalized_import_config(),
            "south-korea-2026-09-01.osm.pbf",
        )

    def test_normalized_import_config_rejects_foot_profile(self) -> None:
        config = self.normalized_import_config()
        config["profiles"] = [{"name": "foot", "custom_model": {"priority": []}}]

        with self.assertRaisesRegex(contract.ContractError, "run profile"):
            contract.validate_normalized_import_config(
                config,
                "south-korea-2026-09-01.osm.pbf",
            )


if __name__ == "__main__":
    unittest.main()
