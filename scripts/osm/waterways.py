#!/usr/bin/env python3
"""물길 인덱스 조회 — `build_waterways.py` 산출물을 읽어 출발점 근처 물길을 찾는다.

서버가 요청 때 하는 일이 이 파일에 다 있다.

    idx = load("data/waterways.json")
    rv  = near(idx, 37.5246, 126.9203, radius=2500)   # 격자 조회
    fs  = features(rv)                                 # 리본 폴리곤
    → GraphHopper 요청의 custom_model.areas 로 넘긴다

**버퍼 폭을 물길 종류별로 다르게 준다.** 한강처럼 폭이 1km 인 강은 중심선에서 60m 만
두르면 물 위만 덮고 둔치 산책로는 버퍼 밖으로 빠진다. 실측에서 목동·잠실이 이것 때문에
0% 로 나왔다.
"""
from __future__ import annotations

import json
import math
from pathlib import Path

#: 물길 종류·규모별 버퍼 폭(m). 본류는 둔치까지 멀다.
BIG_RIVER_LENGTH = 15000.0


def buf_of(w: dict) -> float:
    """중심선에서 좌우로 두를 폭(m)."""
    if w["t"] == "river":
        return 400.0 if w["l"] >= BIG_RIVER_LENGTH else 200.0
    if w["t"] == "canal":
        return 120.0
    return 60.0


def meters(a: tuple[float, float], b: tuple[float, float]) -> float:
    la1, ln1, la2, ln2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((ln2 - ln1) / 2) ** 2
    return 6371000 * 2 * math.asin(math.sqrt(h))


def load(path: str | Path) -> dict:
    return json.loads(Path(path).expanduser().read_text(encoding="utf-8"))


def near(idx: dict, lat: float, lng: float, radius: float = 2500.0) -> list[dict]:
    """반경 안 물길을 **물가까지의 거리** 오름차순으로.

    중심선까지가 아니라 버퍼 가장자리까지를 잰다. 한강은 중심선이 1.5km 밖이어도
    강변 산책로는 1km 안이다.
    """
    cell = idx["cell"]
    span = int(math.ceil(radius / 111320 / cell)) + 1
    ci, cj = int(math.floor(lat / cell)), int(math.floor(lng / cell))
    hit: set[int] = set()
    for i in range(ci - span, ci + span + 1):
        for j in range(cj - span, cj + span + 1):
            hit.update(idx["grid"].get(f"{i},{j}", ()))

    out = []
    for k in hit:
        w = idx["ways"][k]
        d = min(meters((lat, lng), (p[0], p[1])) for p in w["p"])
        if d > radius:
            continue
        out.append({**w, "d": d, "buf": buf_of(w), "eff": max(0.0, d - buf_of(w))})
    out.sort(key=lambda w: w["eff"])
    return out


def ribbon(pts: list[list[float]], buf: float) -> list[list[float]] | None:
    """폴리라인을 좌우로 벌려 닫힌 링([lng, lat])으로. shapely 없이 계산한다."""
    if len(pts) < 2:
        return None
    left, right = [], []
    for i, (la, lo) in enumerate(pts):
        a = pts[max(0, i - 1)]
        b = pts[min(len(pts) - 1, i + 1)]
        dy = b[0] - a[0]
        dx = (b[1] - a[1]) * math.cos(math.radians(la))
        n = math.hypot(dx, dy) or 1e-9
        ox = -dy / n * (buf / 111320) / max(math.cos(math.radians(la)), 1e-6)
        oy = dx / n * (buf / 111320)
        left.append([lo + ox, la + oy])
        right.append([lo - ox, la - oy])
    return left + right[::-1] + [left[0]]


def features(rv: list[dict], limit: int = 6) -> list[dict]:
    """GraphHopper `custom_model.areas` 에 넣을 GeoJSON Feature 목록."""
    out = []
    for i, w in enumerate(rv[:limit]):
        ring = ribbon(w["p"], w["buf"])
        if ring:
            out.append({"type": "Feature", "id": f"river{i}", "properties": {},
                        "geometry": {"type": "Polygon", "coordinates": [ring]}})
    return out


def entry_point(w: dict, lat: float, lng: float) -> tuple[float, float, float]:
    """물길이 멀 때 시작할 **강변 진입점**과 거기까지 거리(m).

    중심선 최근접점에서 출발점 쪽으로 버퍼만큼 당긴다. GraphHopper 가 가까운
    보행로에 스냅하므로 정확히 물가일 필요는 없다.
    """
    c = min(w["p"], key=lambda p: meters((lat, lng), (p[0], p[1])))
    cd = meters((lat, lng), (c[0], c[1])) or 1.0
    t = min(1.0, w["buf"] / cd)
    p = (c[0] + (lat - c[0]) * t, c[1] + (lng - c[1]) * t)
    return p[0], p[1], meters((lat, lng), p)
