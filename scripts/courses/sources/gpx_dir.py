"""로컬 GPX 폴더 어댑터 — 산림청 등산로처럼 파일로 받는 소스에 쓴다.

API 가 없고 사람이 내려받아야 하는 소스가 많다. 그런 건 `data/` 아래 폴더에 풀어두고
이 어댑터로 한 번에 읽는다. **하위 폴더까지 재귀로 훑는다** — 공공데이터포털 배포본이
`{산}/{산}_0000000001.gpx` 처럼 중첩돼 있기 때문이다.

    python scripts/build_courses.py --sources "gpx:100대명산"
    python scripts/build_courses.py --sources "gpx:한국등산트레킹지원센터_국가숲길 코스_20230825"

**코스 이름은 파일명에서 뽑는다.** GPX 안의 `<name>` 이 쓸 만한 경우가 드물다 —
국가숲길은 전부 `기타`, 100대명산은 코스명이 아니라 시작지점명(`산림문화휴양관 주차장`)이
들어 있다. 파일명 `가리산_0000000001.gpx` → `가리산 1코스`.

지역(sido/sigun)은 파일에 없으므로 `--regeocode` 로 좌표에서 채운다.

`meta.json` 을 폴더에 두면 파일별로 보강할 수 있다(없어도 동작):

    {"가리산_0000000001.gpx": {"name": "가리산 정상 코스", "level": 3}}
"""
from __future__ import annotations

import json
import logging
import re
from collections.abc import Iterator
from pathlib import Path

from ..model import RawCourse
from .base import CourseSource, register

log = logging.getLogger(__name__)

ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "data"

_TRK = re.compile(r"<trk>(.*?)</trk>", re.S)
_TRKPT = re.compile(r'<trkpt\s+lat="([-\d.]+)"\s+lon="([-\d.]+)"\s*>(.*?)</trkpt>', re.S)
_ELE = re.compile(r"<ele>([-\d.]+)</ele>")


def _points_of(fragment: str) -> list[tuple[float, float, float]]:
    out = []
    for m in _TRKPT.finditer(fragment):
        ele = _ELE.search(m.group(3))
        out.append(
            (float(m.group(1)), float(m.group(2)), float(ele.group(1)) if ele else 0.0)
        )
    return out


def parse_gpx(xml: str) -> list[tuple[float, float, float]]:
    """가장 긴 `<trk>` 하나를 쓴다. 여러 트랙을 이어붙이면 사이 점프가 거리에 더해진다."""
    tracks = [p for t in _TRK.findall(xml) if len(p := _points_of(t)) >= 2]
    return max(tracks, key=len) if tracks else _points_of(xml)


def course_name(path: Path) -> str:
    """`가리산_0000000001.gpx` → `가리산 1코스`. 번호가 없으면 파일명 그대로."""
    base, _, tail = path.stem.rpartition("_")
    if base and tail.isdigit():
        return f"{base} {int(tail)}코스"
    return path.stem


@register
class GpxDir(CourseSource):
    key = "gpx"
    attribution = ""  # 폴더마다 다르다. LICENSE.txt 를 같이 두고 build 리포트로 확인한다.
    license = "소스별로 다름 — 폴더에 LICENSE.txt 를 같이 둘 것"
    derivable = True

    def __init__(self, folder: str = "", attribution: str = "", derivable: bool = True):
        if not folder:
            raise SystemExit('gpx 어댑터는 폴더 이름이 필요하다: --sources "gpx:100대명산"')
        # data/{폴더} 를 먼저 보고, 없으면 data/courses_gpx/{폴더} 를 본다.
        for cand in (DATA / folder, DATA / "courses_gpx" / folder):
            if cand.is_dir():
                self.dir = cand
                break
        else:
            raise SystemExit(f"data/{folder} 가 없다. GPX 를 이 폴더에 풀어라.")

        self.folder = folder
        self.attribution = attribution or folder
        self.derivable = derivable
        meta_path = self.dir / "meta.json"
        self.meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.exists() else {}

    def fetch(self) -> Iterator[RawCourse]:
        seen: set[str] = set()
        for path in sorted(self.dir.rglob("*.gpx")):
            points = parse_gpx(path.read_text(encoding="utf-8", errors="replace"))
            if len(points) < 2:
                log.warning("%s: 트랙 좌표 없음 — 건너뜀(POI 전용 파일일 수 있다)", path.name)
                continue

            extra = self.meta.get(path.name, {})
            # id 는 폴더 안에서 유일해야 한다. 하위 폴더가 달라도 파일명이 겹칠 수 있다.
            cid = f"{self.folder}_{path.parent.name}_{path.stem}"
            if cid in seen:
                log.warning("%s: id 중복 — 건너뜀", cid)
                continue
            seen.add(cid)

            yield RawCourse(
                id=cid,
                name=extra.get("name") or course_name(path),
                points=points,
                sido=extra.get("sido", ""),
                sigun=extra.get("sigun", ""),
                level=extra.get("level"),
                cycle=extra.get("cycle", ""),
                summary=extra.get("summary", ""),
                data_source="GPX_FILE",
            )
