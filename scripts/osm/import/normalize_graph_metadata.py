#!/usr/bin/env python3
"""GraphHopper가 graph에 기록한 실행 시각을 결정적 값으로 정규화한다."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


FIXED_TIMESTAMP = b"1970-01-01T00:00:00Z"
VOLATILE_KEYS = (
    b"datareader.import.date",
    b"prepare.lm.date.run",
)
TIMESTAMP = rb"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"


def normalize_file(path: Path) -> None:
    try:
        content = path.read_bytes()
    except OSError as error:
        raise ValueError(f"GraphHopper metadata 파일을 읽을 수 없습니다: {path}") from error

    for key in VOLATILE_KEYS:
        pattern = re.compile(re.escape(key) + rb"=" + TIMESTAMP)
        content, replacements = pattern.subn(key + b"=" + FIXED_TIMESTAMP, content)
        if replacements != 1:
            raise ValueError(
                f"{path.name}에서 {key.decode()}를 정확히 한 번 찾지 못했습니다."
            )

    try:
        path.write_bytes(content)
    except OSError as error:
        raise ValueError(f"GraphHopper metadata 파일을 쓸 수 없습니다: {path}") from error


def normalize_graph(graph_dir: Path) -> None:
    if not graph_dir.is_dir():
        raise ValueError(f"GraphHopper graph directory가 없습니다: {graph_dir}")
    for name in ("properties", "properties.txt"):
        normalize_file(graph_dir / name)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="GraphHopper graph의 실행 시각 metadata를 SOURCE_DATE_EPOCH로 정규화합니다."
    )
    parser.add_argument("--graph-dir", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        normalize_graph(args.graph_dir)
    except ValueError as error:
        print(f"graph metadata 정규화 실패: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
