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
import math
import sys
from pathlib import Path

import requests

BASE = "http://127.0.0.1:8989"

#: 러닝에 좋은 길. 보행로·산책로·자전거도로 계열.
GOOD = {"footway", "path", "pedestrian", "cycleway", "track"}
#: 차도. 보도가 있어도 신호와 차량 옆이라 러닝 경험이 나쁘다.
ROAD = {"primary", "secondary", "trunk", "tertiary", "motorway"}
#: 주택가 골목·이면도로. 차도도 아니고 보행로도 아니라 예전엔 어디에도 안 잡혔다.
#: 실측에서 수도권 5km 경로의 중앙 15%, 서울 강서는 73% 가 이것이었다.
ALLEY = {"residential", "living_street", "service"}
#: 계단. 뛰던 흐름이 끊긴다.
STEPS = {"steps"}

#: 길 종류별 러닝 품질. **graphhopper.yml 의 run 프로파일 가중치와 같은 값**이다.
#: 측정과 라우팅이 다른 잣대를 쓰면 "좋다고 재놓고 나쁜 길을 고르는" 일이 생긴다.
#: living_street 은 라우팅 기준(0.5)에 맞춘다 — 예전엔 측정에서만 좋은길이었다.
QUALITY = {
    "footway": 1.00, "path": 1.00, "pedestrian": 1.00,
    "cycleway": 0.95, "track": 0.95,
    "residential": 0.50, "living_street": 0.50,
    "service": 0.25,
    "tertiary": 0.20,
    "primary": 0.05, "secondary": 0.05, "trunk": 0.05, "motorway": 0.05,
    "steps": 0.05,
}
#: 표에 없는 값(unclassified 등)의 기본 점수.
QUALITY_DEFAULT = 0.40

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

#: 수도권 밖. 계약 커버리지가 수도권 기준으로만 잡혀 있어 대조군으로 둔다.
LOCAL = [
    ("부산 해운대", 35.1631, 129.1636), ("부산 서면", 35.1578, 129.0594),
    ("대구 수성", 35.8580, 128.6300), ("광주 상무", 35.1520, 126.8500),
    ("대전 유성", 36.3620, 127.3560), ("울산 남구", 35.5384, 129.3114),
    ("청주 흥덕", 36.6300, 127.4300), ("전주 덕진", 35.8420, 127.1300),
    ("창원 성산", 35.2280, 128.6810), ("강릉 교동", 37.7700, 128.8900),
]


def meters(a: list[float], b: list[float]) -> float:
    """두 좌표([lng, lat, ele]) 사이 거리(m). haversine."""
    la1, ln1, la2, ln2 = map(math.radians, (a[1], a[0], b[1], b[0]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((ln2 - ln1) / 2) ** 2
    return 6371000 * 2 * math.asin(math.sqrt(h))


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
    return _parse(r.json()["paths"][0], seed)


def _parse(p: dict, seed: int) -> dict:
    """GraphHopper path 하나를 지표로 환산한다."""
    pts = p["points"]["coordinates"]          # [lng, lat, ele]

    # 길 종류별 **실제 거리**를 잰다. details 의 a·b 는 좌표점 번호라 개수로 세면
    # 좌표가 촘촘한 굽은 길이 부풀고 곧은 차도는 줄어든다.
    seg = [meters(pts[i - 1], pts[i]) for i in range(1, len(pts))]
    agg: collections.Counter = collections.Counter()
    for a, b, v in p.get("details", {}).get("road_class", []):
        agg[v] += sum(seg[a:b])
    total = max(1.0, sum(agg.values()))
    steps = p.get("instructions") or []
    return {
        "km": p["distance"] / 1000,
        "good": sum(c for v, c in agg.items() if v in GOOD) * 100 / total,
        "road": sum(c for v, c in agg.items() if v in ROAD) * 100 / total,
        "stair": sum(c for v, c in agg.items() if v in STEPS) * 100 / total,
        "alley": sum(c for v, c in agg.items() if v in ALLEY) * 100 / total,
        # 0~100 점. 모든 구간이 들어가므로 좋은길·차도처럼 빠지는 몫이 없다.
        "qual": sum(QUALITY.get(v, QUALITY_DEFAULT) * c for v, c in agg.items()) * 100 / total,
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
        f"{'지점':13} {'목표':>5} {'실제':>8} {'품질':>5} {'좋은길':>6} {'골목':>6} {'차도':>6} "
        f"{'계단':>6} {'턴/km':>6} {'상승':>7}"
    )
    print("-" * 84)


def show(name: str, km: float, b: dict | None) -> None:
    if not b:
        print(f"{name:13} {km:4.0f}km  실패")
        return
    print(
        f"{name:13} {km:4.0f}km {b['km']:7.2f}km {b['qual']:4.0f}  {b['good']:5.1f}% "
        f"{b['alley']:5.1f}% {b['road']:5.1f}% {b['stair']:5.1f}% "
        f"{b['turns'] / max(b['km'], 0.1):6.1f} {b['gain']:5.0f}m"
    )


# ── 필터 기준 잡기 ────────────────────────────────────────────

#: 필터 통계를 낼 목표 거리. 짧을수록 좁은 데서 돌아 많이 꺾인다.
FILTER_KMS = (5, 10, 21)
#: 훑어볼 차도 비율 상한 후보(%).
ROAD_CAPS = (5, 10, 15, 20, 30)
#: 훑어볼 km 당 방향 전환 상한 후보.
TURN_CAPS = (3, 4, 6, 8, 10)
#: 훑어볼 골목 비율 상한 후보(%).
ALLEY_CAPS = (20, 30, 40, 50)
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


# ── 골목 회피 · 하천 유도 ──────────────────────────────────────

#: 골목 회피 프로파일. 요청 단위 custom model 로 residential/service 를 더 깎는다.
#: 라우팅 프로파일은 이미 0.5/0.25 를 주고 있으므로 실효 0.15/0.075 가 된다.
ALLEY_PEN = "0.3"
#: 하천 버퍼 밖 가중치. 0.25~0.6 을 훑어 0.4 가 가장 나았다(차이는 작다).
RIVER_PEN = "0.4"
#: 하천이 이보다 멀면 강변 진입점으로 출발점을 옮긴다(m).
SEED_SHIFT = 800.0
#: 진입점까지 걸어갈 수 있는 상한(m).
MAX_ACCESS = 1200.0


def route_cm(lat, lng, km, seed, feats=None, avoid_alley=False):
    """custom model 을 실어 보내는 round_trip. GET 으로는 못 보내 POST 를 쓴다."""
    body = {
        "points": [[lng, lat]], "profile": "run", "algorithm": "round_trip",
        "round_trip.distance": int(km * 1000), "round_trip.seed": seed,
        "points_encoded": False, "elevation": True, "instructions": True,
        "details": ["road_class"],
    }
    pri, cm = [], {}
    if feats:
        pri.append({"if": " && ".join(f"!in_{f['id']}" for f in feats), "multiply_by": RIVER_PEN})
        cm["areas"] = {"type": "FeatureCollection", "features": feats}
    if avoid_alley:
        pri.append({"if": "road_class == RESIDENTIAL || road_class == SERVICE",
                    "multiply_by": ALLEY_PEN})
    if pri:
        cm["priority"] = pri
        body["custom_model"] = cm
    r = requests.post(f"{BASE}/route", json=body, timeout=60)
    if r.status_code != 200:
        return None
    return _parse(r.json()["paths"][0], seed)


def contract_pick(cands: list[dict], km: float) -> dict | None:
    """지금 계약의 선택 규칙 — 차도 ≤5% 우선 → 거리 오차 → 회전/km → 차도."""
    if not cands:
        return None
    clean = [c for c in cands if c["road"] <= 5] or cands
    return min(clean, key=lambda c: (abs(c["km"] - km),
                                     c["turns"] / max(c["km"], 0.1), c["road"]))


def run_score(c: dict, km: float, access_m: float = 0.0) -> float:
    """**뛰기 좋은 정도**. 계약 규칙은 거리를 먼저 보므로 품질이 밀린다.

    물가 비율은 넣지 않는다 — 물가가 좋은 이유는 물이 아니라 신호·차가 없어서다.
    그 조건(길품질·회전)을 직접 재면 동네가 좋을 때는 동네가 이긴다.
    """
    return (c["qual"]
            - max(0.0, c["turns"] / max(c["km"], 0.1) - 3.0) * 3.0
            - max(0.0, c["gain"] / max(c["km"], 0.1) - 15.0) * 0.5
            - access_m / 100.0
            - abs(c["km"] - km) * 1000 * 0.01)


def passes_caps(c: dict, km: float) -> bool:
    """SPEC §5.8 품질 상한 네 개."""
    return (km * 0.75 <= c["km"] <= km * 1.25
            and c["gain"] / max(c["km"], 0.1) < 50
            and c["road"] <= 10
            and c["turns"] / max(c["km"], 0.1) <= 6)


def water_stats(seeds: int, index_path: str) -> None:
    """골목 회피와 하천 유도가 실제로 코스를 낫게 하는지 — 수도권·지방 대조."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import waterways as W

    idx = W.load(index_path)
    pts = [(n, a, b, "수도권") for n, a, b in METRO] + [(n, a, b, "지방") for n, a, b in LOCAL]
    rows = []
    for nm, la, lo, zone in pts:
        rv = W.near(idx, la, lo)
        fs = W.features(rv)
        ent = None
        if rv and rv[0]["eff"] > SEED_SHIFT:
            p = W.entry_point(rv[0], la, lo)
            if p[2] <= MAX_ACCESS:
                ent = p
        for km in (5, 10):
            def gen(alat, alng, feats=None, avoid=False):
                out = []
                for s in range(seeds):
                    g = route_cm(alat, alng, km * DISTANCE_CORRECTION, s, feats, avoid)
                    if g and passes_caps(g, km):
                        out.append(g)
                return out

            plain = gen(la, lo)
            alley = gen(la, lo, None, True)
            water = []
            if rv and rv[0]["eff"] <= SEED_SHIFT:
                water = gen(la, lo, fs) + gen(la, lo, fs, True)
            elif ent:
                water = gen(ent[0], ent[1], fs) + gen(ent[0], ent[1], fs, True)
            pool = plain + alley + water
            best_score = max(pool, key=lambda c: run_score(c, km)) if pool else None
            rows.append((nm, zone, km,
                         contract_pick(plain, km),
                         contract_pick(plain + alley, km),
                         contract_pick(pool, km),
                         best_score))

    def rep(label: str, i: int, zone: str | None) -> None:
        v = [(r[i], r[2]) for r in rows if r[i] and (zone is None or r[1] == zone)]
        tot = len([r for r in rows if zone is None or r[1] == zone])
        if not v:
            print(f"  {label:24} 경로 0/{tot}")
            return
        print(f"  {label:24} 경로 {len(v):2}/{tot}"
              f" · 품질 {pct([x['qual'] for x, _ in v], 0.5):3.0f}"
              f" · 골목 {pct([x['alley'] for x, _ in v], 0.5):4.1f}%"
              f" · 골목>40% {sum(1 for x, _ in v if x['alley'] > 40):2}건"
              f" · 차도 {pct([x['road'] for x, _ in v], 0.5):4.1f}%"
              f" · 거리오차 {pct([abs(x['km'] - k) for x, k in v], 0.5) * 1000:4.0f}m")

    for zone in (None, "수도권", "지방"):
        print(f"[{zone or '전체'}]")
        rep("A 현행 계약", 3, zone)
        rep("B +골목 회피", 4, zone)
        rep("C +골목+하천 · 계약규칙", 5, zone)
        rep("D +골목+하천 · 점수", 6, zone)
        print()


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
    roads = [min(x["road"] for x in c) for c in got]
    alleys = [min(x["alley"] for x in c) for c in got]
    quals = [max(x["qual"] for x in c) for c in got]
    stairs = [min(x["stair"] for x in c) for c in got]
    turns = [min(x["turns"] / max(x["km"], 0.1) for x in c) for c in got]
    steps = [min(x["steps"] / max(x["km"], 0.1) for x in c) for c in got]

    print(f"분포 — 지점·거리 {len(got)}/{len(pool)} 건 생성 성공 (지표별 최선 후보 기준)")
    spread("차도", roads, "%")
    spread("골목", alleys, "%")
    spread("길품질", quals)
    spread("계단", stairs, "%")
    spread("턴/km", turns)
    spread("안내/km", steps)
    print("  ※ 안내/km 는 옛 계산(직진·도착 포함). 턴/km 와의 차이가 곧 과장분이다.")

    print("\n기준별 커버리지 — 후보가 한 건이라도 남는 지점·거리 비율")
    total = len(pool)
    for cap in ROAD_CAPS:
        n = sum(1 for _, _, c in pool if any(x["road"] <= cap for x in c))
        print(f"  차도 ≤ {cap:2}%              {n:3}/{total} ({n * 100 // total}%)")
    print()
    for cap in ALLEY_CAPS:
        n = sum(1 for _, _, c in pool if any(x["alley"] <= cap for x in c))
        print(f"  골목 ≤ {cap:2}%              {n:3}/{total} ({n * 100 // total}%)")
    print("  ※ 골목은 상한을 걸면 커버리지가 무너진다 — 거르지 말고 고르는 문제다.")
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
    ap.add_argument("--preset", choices=("metro", "compare", "distance", "filter", "water"))
    ap.add_argument("--waterways", default="data/waterways.json",
                    help="build_waterways.py 산출물 (preset=water)")
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

    elif args.preset == "water":
        water_stats(args.seeds, args.waterways)

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
