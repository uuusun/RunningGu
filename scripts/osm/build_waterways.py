#!/usr/bin/env python3
"""전국 물길 인덱스 생성 — OSM PBF 에서 하천을 뽑아 격자 인덱스로 저장한다.

도시 러닝코스가 동네 골목을 도는 대신 천변을 타게 하려면, 출발점 근처에 어떤 물길이
있는지 순간에 알아야 한다. 카카오 걷기 스팟으로는 안 된다 — 실측에서 안양천이 목록에
안 나왔다. 카카오는 점으로 찍힌 **장소**를 주지 선형 지형을 주지 않기 때문이다.

OSM 은 하천을 way 로 갖고 있어서 이름이 없어도 잡힌다(경로 구간의 68% 가 무명이다).

    python scripts/osm/build_waterways.py ~/osm-poc/korea.osm.pbf -o data/waterways.json

산출물은 배포 단계에서 한 번 만들어 서버가 들고 있는다. GraphHopper 그래프 캐시와 같은
자리에 두면 된다. 요청 때는 격자 조회 → 리본 폴리곤 → custom_model areas 로 넘긴다.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import time
from pathlib import Path

import osmium

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

#: 뽑을 물길 종류. riverbank·drain·ditch 는 러닝 코스와 무관해 뺀다.
KINDS = {"river", "stream", "canal"}
#: 이보다 짧은 물길은 버린다(m). 실개천 조각이 인덱스를 불린다.
MIN_LENGTH = 300.0
#: 좌표를 이 간격으로 솎는다(m). 버퍼 폭이 60~400m 라 이 정도면 형태가 유지된다.
RESAMPLE = 50.0
#: 격자 한 칸 크기(도). 약 1.1km — 반경 2km 조회에 9칸이면 충분하다.
CELL = 0.01


def meters(a: tuple[float, float], b: tuple[float, float]) -> float:
    """(lat, lng) 두 점 사이 거리(m). haversine."""
    la1, ln1, la2, ln2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((ln2 - ln1) / 2) ** 2
    return 6371000 * 2 * math.asin(math.sqrt(h))


def resample(pts: list[tuple[float, float]], step: float = RESAMPLE) -> list[tuple[float, float]]:
    """일정 간격으로 솎는다. 첫 점과 끝 점은 항상 남긴다."""
    if len(pts) < 3:
        return pts
    out = [pts[0]]
    acc = 0.0
    for i in range(1, len(pts)):
        acc += meters(pts[i - 1], pts[i])
        if acc >= step:
            out.append(pts[i])
            acc = 0.0
    if out[-1] != pts[-1]:
        out.append(pts[-1])
    return out


class WaterwayHandler(osmium.SimpleHandler):
    """way 를 훑어 물길만 모은다. node 를 따로 안 읽어도 되게 locations 를 켠다."""

    def __init__(self) -> None:
        super().__init__()
        self.ways: list[dict] = []
        self.seen = 0

    def way(self, w) -> None:
        kind = w.tags.get("waterway")
        if kind not in KINDS:
            return
        self.seen += 1
        try:
            pts = [(n.lat, n.lon) for n in w.nodes if n.location.valid()]
        except osmium.InvalidLocationError:
            return
        if len(pts) < 2:
            return
        length = sum(meters(pts[i - 1], pts[i]) for i in range(1, len(pts)))
        if length < MIN_LENGTH:
            return
        self.ways.append({
            "t": kind,
            "n": w.tags.get("name", ""),
            "l": round(length),
            "p": [[round(la, 5), round(lo, 5)] for la, lo in resample(pts)],
        })


def build_grid(ways: list[dict]) -> dict[str, list[int]]:
    """물길이 지나는 격자 칸마다 way 번호를 달아 둔다."""
    grid: dict[str, list[int]] = {}
    for i, w in enumerate(ways):
        cells = set()
        for la, lo in w["p"]:
            cells.add(f"{int(math.floor(la / CELL))},{int(math.floor(lo / CELL))}")
        # set 은 문자열 해시 순서를 따라 실행마다 달라진다. 정렬해서 결정적으로 만든다.
        for c in sorted(cells):
            grid.setdefault(c, []).append(i)
    return dict(sorted(grid.items()))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("pbf", help="OSM PBF 경로 (예: ~/osm-poc/korea.osm.pbf)")
    ap.add_argument("-o", "--out", default="data/waterways.json")
    args = ap.parse_args()

    src = Path(args.pbf).expanduser()
    if not src.exists():
        print(f"PBF 가 없다: {src}")
        return 1

    t0 = time.time()
    h = WaterwayHandler()
    # locations=True 로 node 좌표를 way 에 붙인다. 메모리 대신 디스크 인덱스를 쓴다.
    h.apply_file(str(src), locations=True, idx="flex_mem")
    took = time.time() - t0

    grid = build_grid(h.ways)
    out = Path(args.out).expanduser()
    out.parent.mkdir(parents=True, exist_ok=True)
    payload = {"cell": CELL, "resample": RESAMPLE, "min_length": MIN_LENGTH,
               "ways": h.ways, "grid": grid}
    out.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    kinds: dict[str, int] = {}
    for w in h.ways:
        kinds[w["t"]] = kinds.get(w["t"], 0) + 1
    pts = sum(len(w["p"]) for w in h.ways)
    print(f"물길 way   {h.seen:,} 개 중 {len(h.ways):,} 개 채택 ({MIN_LENGTH:.0f}m 이상)")
    print(f"  종류      {kinds}")
    print(f"  좌표      {pts:,} 개 ({RESAMPLE:.0f}m 간격으로 솎음)")
    print(f"  총 길이    {sum(w['l'] for w in h.ways) / 1000:,.0f} km")
    print(f"  격자      {len(grid):,} 칸 ({CELL}도 ≈ 1.1km)")
    print(f"  파일      {out} · {out.stat().st_size / 1024 / 1024:.1f} MB")
    print(f"  소요      {took:.0f}초 (PBF 읽기)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
