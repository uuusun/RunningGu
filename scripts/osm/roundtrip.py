#!/usr/bin/env python3
"""GraphHopper round_trip 검증 스크립트. — OSM 러닝코스 PoC

기동 중인 GraphHopper 에 순환 경로를 요청해 **러닝코스로 쓸 만한지** 를 본다.
거리만 보는 게 아니라 어떤 길로 지나는지(차도 비율)를 같이 재는 것이 요점이다.

사용:
    python scripts/osm/roundtrip.py --preset metro          # 수도권 20곳 5km
    python scripts/osm/roundtrip.py --preset compare        # foot vs run 비교
    python scripts/osm/roundtrip.py --preset distance       # 5·10·15·21km
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
    return {
        "km": p["distance"] / 1000,
        "good": sum(c for v, c in agg.items() if v in GOOD) * 100 // total,
        "road": sum(c for v, c in agg.items() if v in ROAD) * 100 // total,
        "turns": len(p.get("instructions") or []),
        "gain": sum(
            max(0, pts[i][2] - pts[i - 1][2])
            for i in range(1, len(pts)) if len(pts[i]) > 2
        ),
        "seed": seed,
    }


def best(lat: float, lng: float, km: float, profile: str = "run", seeds: int = 16) -> dict | None:
    """seed 를 여러 개 돌려 목표에 맞으면서 차도가 적은 것을 고른다."""
    cands = []
    for s in range(seeds):
        got = route(lat, lng, km * DISTANCE_CORRECTION, s, profile)
        if got and km * 0.75 <= got["km"] <= km * 1.25:   # 이상치 제외
            cands.append(got)
    if not cands:
        return None
    clean = [c for c in cands if c["road"] <= 5] or cands
    return min(clean, key=lambda c: (abs(c["km"] - km), c["turns"]))


def header() -> None:
    print(f"{'지점':13} {'목표':>5} {'실제':>8} {'좋은길':>6} {'차도':>5} {'턴/km':>6} {'상승':>7}")
    print("-" * 60)


def show(name: str, km: float, b: dict | None) -> None:
    if not b:
        print(f"{name:13} {km:4.0f}km  실패")
        return
    print(
        f"{name:13} {km:4.0f}km {b['km']:7.2f}km {b['good']:5}% {b['road']:4}% "
        f"{b['turns'] / max(b['km'], 0.1):6.1f} {b['gain']:5.0f}m"
    )


def main() -> int:
    ap = argparse.ArgumentParser(description="GraphHopper round_trip 검증")
    ap.add_argument("--preset", choices=("metro", "compare", "distance"))
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
