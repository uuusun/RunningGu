#!/usr/bin/env python3
"""GraphHopper graph artifact의 canonical hash·package·검증 구현."""

from __future__ import annotations

import argparse
import datetime as dt
import gzip
import hashlib
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


SCHEMA_VERSION = 1
GRAPHHOPPER_VERSION = "11.0"
GRAPHHOPPER_JAR_SHA256 = "b59c024afe172ec6ec85b6327006c3138ec58c7d0bcd26253d0e42853f613def"
ARCHIVE_NAME = "graph.tar.gz"
MANIFEST_NAME = "graph-manifest.json"
CHECKSUMS_NAME = "SHA256SUMS"
IGNORED_GRAPH_FILES = {MANIFEST_NAME, "gh.lock"}

HEX_64 = re.compile(r"^[0-9a-f]{64}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
PBF_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
ARTIFACT_ID = re.compile(
    r"^gh11-korea-(?P<date>\d{8})-(?P<input>[0-9a-f]{12})-(?P<graph>[0-9a-f]{12})$"
)


class ContractError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise ContractError(message)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_sha256(value: Any, field: str) -> str:
    if not isinstance(value, str) or not HEX_64.fullmatch(value):
        fail(f"{field}는 64자리 lowercase SHA-256이어야 합니다.")
    return value


def require_digest(value: Any, field: str) -> str:
    if not isinstance(value, str) or not DIGEST.fullmatch(value):
        fail(f"{field}는 sha256:<64자리 lowercase hex> 형식이어야 합니다.")
    return value


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail(f"JSON에 중복 key가 있습니다: {key}")
        result[key] = value
    return result


def load_json(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as stream:
            value = json.load(stream, object_pairs_hook=reject_duplicate_keys)
    except (OSError, json.JSONDecodeError) as error:
        fail(f"JSON을 읽을 수 없습니다: {path}: {error}")
    if not isinstance(value, dict):
        fail(f"JSON 최상위 값은 object여야 합니다: {path}")
    return value


def require_exact_keys(value: dict[str, Any], expected: set[str], field: str) -> None:
    actual = set(value)
    if actual != expected:
        fail(
            f"{field} key가 계약과 다릅니다. "
            f"missing={sorted(expected - actual)}, unexpected={sorted(actual - expected)}"
        )


def safe_relative_path(raw_path: str, field: str) -> PurePosixPath:
    if not raw_path or "\\" in raw_path or any(ord(char) < 32 for char in raw_path):
        fail(f"{field}에 안전하지 않은 경로가 있습니다: {raw_path!r}")
    path = PurePosixPath(raw_path)
    if (
        path.is_absolute()
        or path.as_posix() != raw_path
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        fail(f"{field}는 정규화된 POSIX 상대경로여야 합니다: {raw_path!r}")
    return path


def canonical_file_lines(
    files: Iterable[tuple[str, int, str]],
) -> tuple[str, list[str]]:
    ordered = sorted(files, key=lambda record: record[0].encode("utf-8"))
    lines = [f"{digest} {size} {path}\n" for path, size, digest in ordered]
    if not lines:
        fail("hash 대상 파일이 하나도 없습니다.")
    canonical = "".join(lines).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest(), lines


def tree_file_records(root: Path, ignored: set[str]) -> list[tuple[str, int, str]]:
    if not root.is_dir():
        fail(f"graph directory가 없습니다: {root}")
    records: list[tuple[str, int, str]] = []
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            fail(f"graph tree에 symlink가 있습니다: {path}")
        if path.is_dir():
            continue
        if not path.is_file():
            fail(f"graph tree에 일반 파일이 아닌 항목이 있습니다: {path}")
        relative = path.relative_to(root).as_posix()
        safe_relative_path(relative, "graph tree")
        if relative in ignored:
            continue
        records.append((relative, path.stat().st_size, sha256_file(path)))
    return records


def hash_tree(root: Path, ignored: set[str] = frozenset()) -> str:
    digest, _ = canonical_file_lines(tree_file_records(root, set(ignored)))
    return digest


def build_input_sha256(
    jar_sha256: str,
    import_config_sha256: str,
    pbf_sha256: str,
    srtm_files_sha256: str,
) -> str:
    values = (
        ("graphhopperJarSha256", jar_sha256),
        ("importConfigSha256", import_config_sha256),
        ("pbfSha256", pbf_sha256),
        ("srtmFilesSha256", srtm_files_sha256),
    )
    for key, value in values:
        require_sha256(value, key)
    canonical = "".join(f"{key}={value}\n" for key, value in values).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def is_host_absolute_path(value: str) -> bool:
    return value.startswith("/") or bool(re.match(r"^[A-Za-z]:[\\/]", value))


def reject_host_paths(value: Any, field: str) -> None:
    if isinstance(value, str) and is_host_absolute_path(value):
        fail(f"{field}에 정규화되지 않은 host 절대경로가 있습니다: {value}")
    if isinstance(value, list):
        for index, item in enumerate(value):
            reject_host_paths(item, f"{field}[{index}]")
    if isinstance(value, dict):
        for key, item in value.items():
            reject_host_paths(item, f"{field}.{key}")


def normalize_import_config(config_path: Path, pbf_file_name: str) -> tuple[dict[str, Any], str]:
    normalizer = os.environ.get("RUNNINGGU_IMPORT_CONFIG_NORMALIZER")
    if not normalizer:
        fail("package 명령은 고정 builder 이미지 안에서 실행해야 합니다.")
    try:
        completed = subprocess.run(
            [normalizer, str(config_path), pbf_file_name],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
    except OSError as error:
        fail(f"import 설정 정규화 도구를 실행할 수 없습니다: {error}")
    if completed.returncode != 0:
        fail(f"import 설정 정규화 실패: {completed.stderr.strip()}")
    try:
        allowed = json.loads(completed.stdout, object_pairs_hook=reject_duplicate_keys)
    except json.JSONDecodeError as error:
        fail(f"import 설정 정규화 결과가 JSON이 아닙니다: {error}")
    if not isinstance(allowed, dict):
        fail("import 설정 정규화 결과는 object여야 합니다.")
    reject_host_paths(allowed, "importConfig")
    validate_normalized_import_config(allowed, pbf_file_name)

    canonical = json.dumps(
        allowed,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return allowed, hashlib.sha256(canonical).hexdigest()


def validate_normalized_import_config(allowed: dict[str, Any], pbf_file_name: str) -> None:
    expected_encoded_values = (
        "foot_access, foot_priority, foot_average_speed, average_slope, hike_rating, "
        "road_class, road_environment, surface"
    )
    if allowed.get("datareader.file") != pbf_file_name:
        fail("정규화된 datareader.file과 PBF 파일명이 다릅니다.")
    if allowed.get("import.osm.ignored_highways") != "motorway,trunk":
        fail("운영 import.osm.ignored_highways가 motorway,trunk가 아닙니다.")
    if allowed.get("graph.encoded_values") != expected_encoded_values:
        fail("운영 graph.encoded_values가 승인 목록과 다릅니다.")
    if allowed.get("graph.elevation.provider") != "srtm":
        fail("운영 elevation provider가 srtm이 아닙니다.")
    if allowed.get("graph.elevation.cache_dir") != "$SRTM_CACHE":
        fail("운영 SRTM cache 경로가 canonical 값이 아닙니다.")

    profiles = allowed.get("profiles")
    if not isinstance(profiles, list) or len(profiles) != 1:
        fail("운영 profiles는 run 하나여야 합니다.")
    profile = profiles[0]
    if not isinstance(profile, dict) or profile.get("name") != "run" or "custom_model" not in profile:
        fail("운영 run profile 또는 custom model이 없습니다.")
    if allowed.get("profiles_ch") != []:
        fail("운영 CH profile은 비어 있어야 합니다.")
    if allowed.get("profiles_lm") != [{"profile": "run"}]:
        fail("운영 LM profile은 run 하나여야 합니다.")


def add_archive_entry(archive: tarfile.TarFile, root: Path, path: Path) -> None:
    relative = path.relative_to(root).as_posix()
    safe_relative_path(relative, "archive")
    if relative in IGNORED_GRAPH_FILES:
        return
    info = tarfile.TarInfo(relative)
    info.uid = 0
    info.gid = 0
    info.uname = ""
    info.gname = ""
    info.mtime = 0
    if path.is_dir():
        info.type = tarfile.DIRTYPE
        info.mode = 0o555
        archive.addfile(info)
        return
    if path.is_symlink() or not path.is_file():
        fail(f"archive 대상에 일반 파일·directory가 아닌 항목이 있습니다: {path}")
    info.size = path.stat().st_size
    info.mode = 0o444
    with path.open("rb") as stream:
        archive.addfile(info, stream)


def create_deterministic_archive(graph_dir: Path, output: Path) -> None:
    with output.open("wb") as raw_stream:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw_stream, mtime=0) as gzip_stream:
            with tarfile.open(fileobj=gzip_stream, mode="w", format=tarfile.PAX_FORMAT) as archive:
                for path in sorted(graph_dir.rglob("*")):
                    add_archive_entry(archive, graph_dir, path)


def archive_file_records(path: Path) -> list[tuple[str, int, str]]:
    records: list[tuple[str, int, str]] = []
    seen: set[str] = set()
    try:
        with tarfile.open(path, "r:gz") as archive:
            for member in archive.getmembers():
                normalized = member.name.removeprefix("./").rstrip("/")
                if not normalized:
                    continue
                safe_relative_path(normalized, "archive")
                if normalized in seen:
                    fail(f"archive에 중복 경로가 있습니다: {normalized}")
                seen.add(normalized)
                if member.issym() or member.islnk() or member.isdev() or member.isfifo():
                    fail(f"archive에 허용되지 않은 항목이 있습니다: {normalized}")
                if member.isdir():
                    continue
                if not member.isfile():
                    fail(f"archive에 일반 파일이 아닌 항목이 있습니다: {normalized}")
                if normalized in IGNORED_GRAPH_FILES:
                    fail(f"archive immutable payload에 제외 파일이 포함됐습니다: {normalized}")
                stream = archive.extractfile(member)
                if stream is None:
                    fail(f"archive 파일을 읽을 수 없습니다: {normalized}")
                digest = hashlib.sha256()
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
                records.append((normalized, member.size, digest.hexdigest()))
    except (OSError, tarfile.TarError) as error:
        fail(f"archive를 읽을 수 없습니다: {path}: {error}")
    return records


def utc_created_at(value: str | None) -> str:
    if value:
        try:
            parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            fail("createdAt은 RFC 3339 UTC timestamp여야 합니다.")
        if parsed.tzinfo != dt.timezone.utc:
            fail("createdAt은 UTC Z여야 합니다.")
        return parsed.isoformat(timespec="seconds").replace("+00:00", "Z")
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def package_artifact(args: argparse.Namespace) -> None:
    graph_dir = args.graph_dir.resolve()
    pbf_path = args.pbf.resolve()
    srtm_dir = args.srtm_dir.resolve()
    jar_path = args.jar.resolve()
    output_root = args.output.resolve()
    if not pbf_path.is_file() or not jar_path.is_file() or not srtm_dir.is_dir():
        fail("PBF·GraphHopper JAR·SRTM directory 입력을 모두 확인하십시오.")
    if not PBF_DATE.fullmatch(args.pbf_date):
        fail("pbf-date는 YYYY-MM-DD 형식이어야 합니다.")
    if not args.created_by.strip():
        fail("created-by는 비어 있을 수 없습니다.")
    builder_digest = require_digest(args.builder_image_digest, "builderImageDigest")
    jar_sha = sha256_file(jar_path)
    if jar_sha != GRAPHHOPPER_JAR_SHA256:
        fail(f"GraphHopper JAR SHA-256이 고정값과 다릅니다: {jar_sha}")

    _, import_config_sha = normalize_import_config(args.config.resolve(), pbf_path.name)
    pbf_sha = sha256_file(pbf_path)
    srtm_sha = hash_tree(srtm_dir)
    graph_sha = hash_tree(graph_dir, IGNORED_GRAPH_FILES)
    build_input_sha = build_input_sha256(jar_sha, import_config_sha, pbf_sha, srtm_sha)
    artifact_id = (
        f"gh11-korea-{args.pbf_date.replace('-', '')}-"
        f"{build_input_sha[:12]}-{graph_sha[:12]}"
    )

    output_root.mkdir(parents=True, exist_ok=True)
    final_dir = output_root / artifact_id
    if final_dir.exists():
        fail(f"artifact output이 이미 존재합니다: {final_dir}")
    with tempfile.TemporaryDirectory(prefix=f".{artifact_id}-", dir=output_root) as temporary:
        temporary_dir = Path(temporary)
        archive_path = temporary_dir / ARCHIVE_NAME
        create_deterministic_archive(graph_dir, archive_path)
        archive_sha = sha256_file(archive_path)
        archive_size = archive_path.stat().st_size
        manifest = {
            "schemaVersion": SCHEMA_VERSION,
            "artifactId": artifact_id,
            "buildInputSha256": build_input_sha,
            "graphhopper": {
                "version": GRAPHHOPPER_VERSION,
                "jarSha256": jar_sha,
                "builderImageDigest": builder_digest,
            },
            "source": {
                "pbfFileName": pbf_path.name,
                "pbfDate": args.pbf_date,
                "pbfSha256": pbf_sha,
                "srtmProvider": "srtm",
                "srtmFilesSha256": srtm_sha,
            },
            "importConfig": {
                "normalizedSha256": import_config_sha,
                "profiles": ["run"],
                "landmarkProfiles": ["run"],
            },
            "graph": {
                "filesSha256": graph_sha,
                "createdAt": utc_created_at(args.created_at),
                "createdBy": args.created_by.strip(),
            },
            "archive": {
                "fileName": ARCHIVE_NAME,
                "sha256": archive_sha,
                "sizeBytes": archive_size,
            },
        }
        manifest_path = temporary_dir / MANIFEST_NAME
        write_json(manifest_path, manifest)
        manifest_sha = sha256_file(manifest_path)
        (temporary_dir / CHECKSUMS_NAME).write_text(
            f"{archive_sha}  {ARCHIVE_NAME}\n{manifest_sha}  {MANIFEST_NAME}\n",
            encoding="utf-8",
            newline="\n",
        )
        verify_bundle(
            manifest_path=manifest_path,
            checksums_path=temporary_dir / CHECKSUMS_NAME,
            archive_path=archive_path,
            graph_dir=graph_dir,
            release_descriptor=None,
            expected_artifact_id=artifact_id,
            expected_environment=None,
        )
        os.replace(temporary_dir, final_dir)
    print(artifact_id)


def validate_manifest(manifest: dict[str, Any]) -> None:
    require_exact_keys(
        manifest,
        {"schemaVersion", "artifactId", "buildInputSha256", "graphhopper", "source", "importConfig", "graph", "archive"},
        "manifest",
    )
    if manifest["schemaVersion"] != SCHEMA_VERSION:
        fail("지원하지 않는 manifest schemaVersion입니다.")
    for field in ("graphhopper", "source", "importConfig", "graph", "archive"):
        if not isinstance(manifest[field], dict):
            fail(f"manifest.{field}는 object여야 합니다.")

    graphhopper = manifest["graphhopper"]
    source = manifest["source"]
    import_config = manifest["importConfig"]
    graph = manifest["graph"]
    archive = manifest["archive"]
    require_exact_keys(graphhopper, {"version", "jarSha256", "builderImageDigest"}, "manifest.graphhopper")
    require_exact_keys(source, {"pbfFileName", "pbfDate", "pbfSha256", "srtmProvider", "srtmFilesSha256"}, "manifest.source")
    require_exact_keys(import_config, {"normalizedSha256", "profiles", "landmarkProfiles"}, "manifest.importConfig")
    require_exact_keys(graph, {"filesSha256", "createdAt", "createdBy"}, "manifest.graph")
    require_exact_keys(archive, {"fileName", "sha256", "sizeBytes"}, "manifest.archive")

    if graphhopper["version"] != GRAPHHOPPER_VERSION:
        fail("GraphHopper version이 11.0이 아닙니다.")
    if require_sha256(graphhopper["jarSha256"], "graphhopper.jarSha256") != GRAPHHOPPER_JAR_SHA256:
        fail("GraphHopper JAR SHA-256이 승인 고정값과 다릅니다.")
    require_digest(graphhopper["builderImageDigest"], "graphhopper.builderImageDigest")
    if not isinstance(source["pbfFileName"], str) or Path(source["pbfFileName"]).name != source["pbfFileName"]:
        fail("source.pbfFileName은 directory 없는 파일명이어야 합니다.")
    if not isinstance(source["pbfDate"], str) or not PBF_DATE.fullmatch(source["pbfDate"]):
        fail("source.pbfDate 형식이 잘못됐습니다.")
    try:
        dt.date.fromisoformat(source["pbfDate"])
    except ValueError:
        fail("source.pbfDate가 유효한 날짜가 아닙니다.")
    require_sha256(source["pbfSha256"], "source.pbfSha256")
    if source["srtmProvider"] != "srtm":
        fail("source.srtmProvider는 srtm이어야 합니다.")
    require_sha256(source["srtmFilesSha256"], "source.srtmFilesSha256")
    require_sha256(import_config["normalizedSha256"], "importConfig.normalizedSha256")
    if import_config["profiles"] != ["run"] or import_config["landmarkProfiles"] != ["run"]:
        fail("운영 profile과 LM은 run 하나여야 합니다.")
    graph_sha = require_sha256(graph["filesSha256"], "graph.filesSha256")
    if not isinstance(graph["createdAt"], str) or utc_created_at(graph["createdAt"]) != graph["createdAt"]:
        fail("graph.createdAt은 초 단위 RFC 3339 UTC Z 형식이어야 합니다.")
    if not isinstance(graph["createdBy"], str) or not graph["createdBy"].strip():
        fail("graph.createdBy는 비어 있을 수 없습니다.")
    if archive["fileName"] != ARCHIVE_NAME:
        fail("archive.fileName이 graph.tar.gz가 아닙니다.")
    require_sha256(archive["sha256"], "archive.sha256")
    if not isinstance(archive["sizeBytes"], int) or isinstance(archive["sizeBytes"], bool) or archive["sizeBytes"] <= 0:
        fail("archive.sizeBytes는 양의 정수여야 합니다.")

    build_input = build_input_sha256(
        graphhopper["jarSha256"],
        import_config["normalizedSha256"],
        source["pbfSha256"],
        source["srtmFilesSha256"],
    )
    if require_sha256(manifest["buildInputSha256"], "buildInputSha256") != build_input:
        fail("buildInputSha256 재계산 값이 manifest와 다릅니다.")
    expected_id = (
        f"gh11-korea-{source['pbfDate'].replace('-', '')}-"
        f"{build_input[:12]}-{graph_sha[:12]}"
    )
    if manifest["artifactId"] != expected_id or not ARTIFACT_ID.fullmatch(str(manifest["artifactId"])):
        fail("artifactId가 canonical 입력·graph hash와 다릅니다.")


def parse_checksums(path: Path) -> dict[str, str]:
    checksums: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        fail(f"SHA256SUMS를 읽을 수 없습니다: {error}")
    for line in lines:
        match = re.fullmatch(r"([0-9a-f]{64})  (graph\.tar\.gz|graph-manifest\.json)", line)
        if not match or match.group(2) in checksums:
            fail("SHA256SUMS 형식 또는 항목이 잘못됐습니다.")
        checksums[match.group(2)] = match.group(1)
    if set(checksums) != {ARCHIVE_NAME, MANIFEST_NAME}:
        fail("SHA256SUMS는 graph.tar.gz와 graph-manifest.json 두 항목만 가져야 합니다.")
    return checksums


def validate_release_descriptor(
    descriptor_path: Path,
    manifest_path: Path,
    manifest: dict[str, Any],
    expected_environment: str | None,
) -> None:
    descriptor = load_json(descriptor_path)
    require_exact_keys(
        descriptor,
        {"schemaVersion", "environment", "artifactId", "manifestSha256", "buildInputSha256"},
        "release descriptor",
    )
    if descriptor["schemaVersion"] != SCHEMA_VERSION:
        fail("release descriptor schemaVersion이 1이 아닙니다.")
    if descriptor["environment"] not in {"staging", "production"}:
        fail("release descriptor environment가 허용값이 아닙니다.")
    if expected_environment and descriptor["environment"] != expected_environment:
        fail("release descriptor environment와 배포 환경이 다릅니다.")
    if descriptor["artifactId"] != manifest["artifactId"]:
        fail("release descriptor와 manifest의 artifactId가 다릅니다.")
    if require_sha256(descriptor["manifestSha256"], "descriptor.manifestSha256") != sha256_file(manifest_path):
        fail("release descriptor의 manifest SHA-256이 실제 bytes와 다릅니다.")
    if require_sha256(descriptor["buildInputSha256"], "descriptor.buildInputSha256") != manifest["buildInputSha256"]:
        fail("release descriptor와 manifest의 buildInputSha256이 다릅니다.")


def verify_bundle(
    manifest_path: Path,
    checksums_path: Path | None,
    archive_path: Path | None,
    graph_dir: Path | None,
    release_descriptor: Path | None,
    expected_artifact_id: str | None,
    expected_environment: str | None,
) -> None:
    manifest = load_json(manifest_path)
    validate_manifest(manifest)
    artifact_id = manifest["artifactId"]
    if expected_artifact_id and artifact_id != expected_artifact_id:
        fail("검증 인자의 artifact ID와 manifest가 다릅니다.")
    if release_descriptor:
        validate_release_descriptor(
            release_descriptor,
            manifest_path,
            manifest,
            expected_environment,
        )
    if checksums_path:
        checksums = parse_checksums(checksums_path)
        if checksums[MANIFEST_NAME] != sha256_file(manifest_path):
            fail("SHA256SUMS의 manifest hash가 실제 bytes와 다릅니다.")
        if archive_path is None:
            fail("SHA256SUMS 검증에는 archive가 필요합니다.")
        if checksums[ARCHIVE_NAME] != sha256_file(archive_path):
            fail("SHA256SUMS의 archive hash가 실제 bytes와 다릅니다.")
    if archive_path:
        archive = manifest["archive"]
        if sha256_file(archive_path) != archive["sha256"] or archive_path.stat().st_size != archive["sizeBytes"]:
            fail("archive hash 또는 size가 manifest와 다릅니다.")
        archive_hash, _ = canonical_file_lines(archive_file_records(archive_path))
        if archive_hash != manifest["graph"]["filesSha256"]:
            fail("archive graph file 목록 hash가 manifest와 다릅니다.")
    if graph_dir:
        if hash_tree(graph_dir, IGNORED_GRAPH_FILES) != manifest["graph"]["filesSha256"]:
            fail("압축 해제·활성 graph tree hash가 manifest와 다릅니다.")
    print(f"artifact 검증 성공: {artifact_id}")


def verify_command(args: argparse.Namespace) -> None:
    verify_bundle(
        manifest_path=args.manifest.resolve(),
        checksums_path=args.checksums.resolve() if args.checksums else None,
        archive_path=args.archive.resolve() if args.archive else None,
        graph_dir=args.graph_dir.resolve() if args.graph_dir else None,
        release_descriptor=args.release_descriptor.resolve() if args.release_descriptor else None,
        expected_artifact_id=args.expected_artifact_id,
        expected_environment=args.expected_environment,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    package = subparsers.add_parser("package")
    package.add_argument("--graph-dir", type=Path, required=True)
    package.add_argument("--pbf", type=Path, required=True)
    package.add_argument("--pbf-date", required=True)
    package.add_argument("--srtm-dir", type=Path, required=True)
    package.add_argument("--config", type=Path, required=True)
    package.add_argument("--jar", type=Path, required=True)
    package.add_argument("--builder-image-digest", required=True)
    package.add_argument("--created-by", required=True)
    package.add_argument("--created-at")
    package.add_argument("--output", type=Path, required=True)
    package.set_defaults(handler=package_artifact)

    verify = subparsers.add_parser("verify")
    verify.add_argument("--manifest", type=Path, required=True)
    verify.add_argument("--checksums", type=Path)
    verify.add_argument("--archive", type=Path)
    verify.add_argument("--graph-dir", type=Path)
    verify.add_argument("--release-descriptor", type=Path)
    verify.add_argument("--expected-artifact-id")
    verify.add_argument("--expected-environment", choices=("staging", "production"))
    verify.set_defaults(handler=verify_command)
    return parser


def main() -> int:
    try:
        args = build_parser().parse_args()
        args.handler(args)
        return 0
    except ContractError as error:
        print(f"GraphHopper artifact 계약 검증 실패: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
