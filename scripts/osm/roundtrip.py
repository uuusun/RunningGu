#!/usr/bin/env python3
"""GraphHopper round_trip 검증 스크립트. — OSM 러닝코스 PoC

기동 중인 GraphHopper 에 순환 경로를 요청해 **러닝코스로 쓸 만한지** 를 본다.
거리만 보는 게 아니라 어떤 길로 지나는지(차도 비율)를 같이 재는 것이 요점이다.

사용:
    python scripts/osm/roundtrip.py --preset metro          # 수도권 20곳 5km
    python scripts/osm/roundtrip.py --preset compare        # foot vs run 비교
    python scripts/osm/roundtrip.py --preset distance       # 5·10·15·21km
    python scripts/osm/roundtrip.py --preset filter         # 필터 기준 분포·커버리지
    python scripts/osm/roundtrip.py --point 37.5246,126.9203 --km 10

서버는 `scripts/osm/README.md` 대로 먼저 띄운다.
"""
from __future__ import annotations

import argparse
import collections
import sys

import requests

BASE = "http://127.0.0.1:8989"

#: 러닝에 좋은 길. 보행로·산책로·자전거도로 계열.
GOOD = {"footway", "path", "pedestrian", "cycleway", "track", "living_street"}
#: 차도. 보도가 있어도 신호와 차량 옆이라 러닝 경험이 나쁘다.
ROAD = {"primary", "secondary", "trunk", "tertiary", "motorway"}

#: round_trip 은 요청값보다 길게 낸다. 목표 × 이 값으로 요청해 보정한다(실측 중앙값).
DISTANCE_CORRECTION = 0.78

#: 실제로 몸이 꺾이는 안내만 고른다. GraphHopper `sign` 기준.
#:
#: 안내 목록에는 직진(0)·도착(4)·경유(5)와 길 이름만 바뀌는 구간까지 들어 있어
#: 개수를 그대로 세면 방향 전환이 과장된다. 좌우회전(±2)·급회전(±3)·유턴(±8·-98)과
#: 로터리(6)만 센다. 살짝 꺾임(±1)과 차선 유지(±7)는 러닝 리듬을 끊지 않아 뺀다.
TURN_SIGNS = {-98, -8, -3, -2, 2, 3, 6, 8}

METRO = [
    ("서울 여의도", 37.5246, 126.9203), ("서울 강남역", 37.4979, 127.0276),
    ("서울 잠실", 37.5133, 127.1000), ("서울 상암", 37.5665, 126.8962),
    ("서울 청량리", 37.5800, 127.0470), ("서울 목동", 37.5260, 126.8750),
    ("서울 노원", 37.6543, 127.0568), ("서울 강서", 37.5509, 126.8495),
    ("서울 관악", 37.4784, 126.9516), ("고양 일산", 37.6584, 126.8320),
    ("성남 분당", 37.3800, 127.1180), ("수원 영통", 37.2500, 127.0700),
    ("용인 기흥", 37.2800, 127.1150), ("부천 중동", 37.5035, 126.7660),
    ("안양 평촌", 37.3900, 126.9500), ("인천 송도", 37.3830, 126.6430),
    ("의정부", 37.7380, 127.0340), ("광명", 37.4790, 126.8650),
    ("김포 장기", 37.6400, 126.7150), ("남양주 다산", 37.6100, 127.1600),
]


def route(lat: float, lng: float, km: float, seed: int, profile: str) -> dict | None:
    r = requests.get(
        f"{BASE}/route",
        params={
            "point": f"{lat},{lng}", "profile": profile, "algorithm": "round_trip",
            "round_trip.distance": int(km * 1000), "round_trip.seed": seed,
            "points_encoded": "false", "elevation": "true",
            "instructions": "true", "details": ["road_class"],
        },
        timeout=120,
    )
    if r.status_code != 200:
        return None
    p = r.json()["paths"][0]
    pts = p["points"]["coordinates"]          # [lng, lat, ele]
    agg: collections.Counter = collections.Counter()
    for a, b, v in p.get("details", {}).get("road_class", []):
        agg[v] += b - a
    total = max(1, sum(agg.values()))
    steps = p.get("instructions") or []
    return {
        "km": p["distance"] / 1000,
        "good": sum(c for v, c in agg.items() if v in GOOD) * 100 // total,
        "road": sum(c for v, c in agg.items() if v in ROAD) * 100 // total,
        "turns": sum(1 for s in steps if s.get("sign") in TURN_SIGNS),
        "steps": len(steps),
        "gain": sum(
            max(0, pts[i][2] - pts[i - 1][2])
            for i in range(1, len(pts)) if len(pts[i]) > 2
        ),
        "seed": seed,
    }


def candidates(lat: float, lng: float, km: float, profile: str = "run", seeds: int = 16) -> list[dict]:
    """seed 를 여러 개 돌려 목표 거리 75~125% 안에 든 후보만 남긴다."""
    out = []
    for s in range(seeds):
        got = route(lat, lng, km * DISTANCE_CORRECTION, s, profile)
        if got and km * 0.75 <= got["km"] <= km * 1.25:   # 이상치 제외
            out.append(got)
    return out


def best(lat: float, lng: float, km: float, profile: str = "run", seeds: int = 16) -> dict | None:
    """후보 중 목표에 맞으면서 차도가 적은 것을 고른다."""
    cands = candidates(lat, lng, km, profile, seeds)
    if not cands:
        return None
    clean = [c for c in cands if c["road"] <= 5] or cands
    return min(clean, key=lambda c: (abs(c["km"] - km), c["turns"]))


def header() -> None:
    print(
        f"{'지점':13} {'목표':>5} {'실제':>8} {'좋은길':>6} {'차도':>5} "
        f"{'턴/km':>6} {'안내/km':>8} {'상승':>7}"
    )
    print("-" * 70)


def show(name: str, km: float, b: dict | None) -> None:
    if not b:
        print(f"{name:13} {km:4.0f}km  실패")
        return
    print(
        f"{name:13} {km:4.0f}km {b['km']:7.2f}km {b['good']:5}% {b['road']:4}% "
        f"{b['turns'] / max(b['km'], 0.1):6.1f} {b['steps'] / max(b['km'], 0.1):8.1f} "
        f"{b['gain']:5.0f}m"
    )


# ── 필터 기준 잡기 ────────────────────────────────────────────

#: 필터 통계를 낼 목표 거리. 짧을수록 좁은 데서 돌아 많이 꺾인다.
FILTER_KMS = (5, 10, 21)
#: 훑어볼 차도 비율 상한 후보(%).
ROAD_CAPS = (5, 10, 15, 20, 30)
#: 훑어볼 km 당 방향 전환 상한 후보.
TURN_CAPS = (3, 4, 6, 8, 10)
#: 난이도 경계(m/km). SPEC §5.8 — EASY <15 · NORMAL 15~50 · HARD ≥50.
EASY, HARD = 15, 50


def pct(values: list[float], q: float) -> float:
    """정렬 후 q 분위 값. 표본이 적어 보간 없이 가장 가까운 순번을 쓴다."""
    if not values:
        return 0.0
    s = sorted(values)
    return s[min(len(s) - 1, int(q * len(s)))]


def spread(label: str, values: list[float], unit: str = "") -> None:
    print(
        f"  {label:10} 최소 {min(values):5.1f}{unit} · 중앙 {pct(values, 0.5):5.1f}{unit} · "
        f"상위10% {pct(values, 0.9):5.1f}{unit} · 최대 {max(values):5.1f}{unit}"
    )


def filter_stats(profile: str, seeds: int) -> None:
    """수도권 × 거리별로 후보를 모아 필터 기준을 어디에 그을지 본다.

    `best` 가 고른 한 건이 아니라 **후보 전체**를 본다. 필터는 후보를 거르는
    장치라, 기준을 넘겨도 남는 후보가 있는지가 곧 커버리지이기 때문이다.
    """
    pool: list[tuple[str, float, list[dict]]] = []
    header()
    for km in FILTER_KMS:
        for nm, la, lo in METRO:
            cands = candidates(la, lo, km, profile, seeds)
            pool.append((nm, km, cands))
            show(nm, km, min(cands, key=lambda c: c["road"]) if cands else None)
        print()

    # 지표마다 그 지표에서 가장 좋은 후보를 본다. "어디까지 좋아질 수 있나"가
    # 곧 기준을 그을 수 있는 자리이기 때문이다.
    got = [c for _, _, c in pool if c]
    roads = [float(min(c, key=lambda x: x["road"])["road"]) for c in got]
    turns = [min(x["turns"] / max(x["km"], 0.1) for x in c) for c in got]
    steps = [min(x["steps"] / max(x["km"], 0.1) for x in c) for c in got]

    print(f"분포 — 지점·거리 {len(got)}/{len(pool)} 건 생성 성공 (지표별 최선 후보 기준)")
    spread("차도", roads, "%")
    spread("턴/km", turns)
    spread("안내/km", steps)
    print("  ※ 안내/km 는 옛 계산(직진·도착 포함). 턴/km 와의 차이가 곧 과장분이다.")

    print("\n기준별 커버리지 — 후보가 한 건이라도 남는 지점·거리 비율")
    total = len(pool)
    for cap in ROAD_CAPS:
        n = sum(1 for _, _, c in pool if any(x["road"] <= cap for x in c))
        print(f"  차도 ≤ {cap:2}%              {n:3}/{total} ({n * 100 // total}%)")
    print()
    for cap in TURN_CAPS:
        n = sum(
            1 for _, _, c in pool
            if any(x["turns"] / max(x["km"], 0.1) <= cap for x in c)
        )
        print(f"  턴/km ≤ {cap:2}              {n:3}/{total} ({n * 100 // total}%)")

    print("\n난이도 — 상승 m/km (EASY <15 · NORMAL 15~50 · HARD ≥50)")
    slopes = [min(x["gain"] / max(x["km"], 0.1) for x in c) for c in got]
    spread("상승/km", slopes)
    for cap, name in ((EASY, "평지만"), (HARD, "언덕 제외")):
        n = sum(
            1 for _, _, c in pool
            if any(x["gain"] / max(x["km"], 0.1) < cap for x in c)
        )
        print(f"  {name:8} (<{cap:2}m/km)      {n:3}/{total} ({n * 100 // total}%)")

    print("  거리별 언덕 제외 커버리지")
    for km in FILTER_KMS:
        rows = [c for _, k, c in pool if k == km]
        n = sum(1 for c in rows if any(x["gain"] / max(x["km"], 0.1) < HARD for x in c))
        print(f"    {km:2}km                 {n:3}/{len(rows)}")

    print("\n세 기준 동시 적용 (차도 ∧ 턴 ∧ 언덕 제외)")
    for road_cap in (5, 10):
        for turn_cap in (4, 6, 8):
            n = sum(
                1 for _, _, c in pool
                if any(
                    x["road"] <= road_cap
                    and x["turns"] / max(x["km"], 0.1) <= turn_cap
                    and x["gain"] / max(x["km"], 0.1) < HARD
                    for x in c
                )
            )
            print(
                f"  차도 ≤ {road_cap:2}% ∧ 턴/km ≤ {turn_cap:2} ∧ 언덕 제외   "
                f"{n:3}/{total} ({n * 100 // total}%)"
            )

    print("\n커버리지가 크게 떨어지는 기준은 쓰지 않는다. 걸러낸 자리는 걷기 스팟이 채운다.")


def main() -> int:
    ap = argparse.ArgumentParser(description="GraphHopper round_trip 검증")
    ap.add_argument("--preset", choices=("metro", "compare", "distance", "filter"))
    ap.add_argument("--point", help="lat,lng")
    ap.add_argument("--km", type=float, default=5)
    ap.add_argument("--profile", default="run", choices=("run", "foot"))
    ap.add_argument("--seeds", type=int, default=16)
    args = ap.parse_args()

    try:
        requests.get(f"{BASE}/health", timeout=5)
    except requests.RequestException:
        print(f"{BASE} 에 GraphHopper 가 없다. scripts/osm/README.md 를 보고 먼저 띄워라.")
        return 1

    if args.preset == "metro":
        header()
        ok = 0
        for nm, la, lo in METRO:
            b = best(la, lo, args.km, args.profile, args.seeds)
            show(nm, args.km, b)
            ok += bool(b)
        print(f"\n성공 {ok}/{len(METRO)}곳")

    elif args.preset == "compare":
        print(f"{'지점':13} {'프로파일':8} {'실제':>8} {'좋은길':>6} {'차도':>5} {'턴':>5}")
        print("-" * 56)
        for nm, la, lo in METRO[:5]:
            for prof in ("foot", "run"):
                b = best(la, lo, args.km, prof, args.seeds)
                if b:
                    print(f"{nm:13} {prof:8} {b['km']:7.2f}km {b['good']:5}% {b['road']:4}% {b['turns']:5}")
            print()

    elif args.preset == "distance":
        header()
        for nm, la, lo in (METRO[0], METRO[10]):
            for km in (5, 10, 15, 21):
                show(nm, km, best(la, lo, km, args.profile, args.seeds))
            print()

    elif args.preset == "filter":
        filter_stats(args.profile, args.seeds)

    elif args.point:
        la, lo = (float(x) for x in args.point.split(","))
        header()
        show(args.point, args.km, best(la, lo, args.km, args.profile, args.seeds))
    else:
        ap.print_help()
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
