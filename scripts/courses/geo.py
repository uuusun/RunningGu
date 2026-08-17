"""좌표 계산 유틸.

거리·고도 계산은 **GPX 원본 해상도**에서 한다. 축약본으로 상승고도를 계산하면
잔잔한 오르내림이 사라져 실측에서 41% 과소평가됐다(해파랑길 1코스 732m → 434m).
그래서 `cum_gain` 을 원본에서 구해 축약 포인트에 실어 나른다.
"""
from __future__ import annotations

import math

EARTH_R = 6_371_000.0


def haversine_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    """두 위경도 사이 거리(m)."""
    lat1, lng1 = a[0], a[1]
    lat2, lng2 = b[0], b[1]
    d_lat = math.radians(lat2 - lat1)
    d_lng = math.radians(lng2 - lng1)
    s = (
        math.sin(d_lat / 2) ** 2
        + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(d_lng / 2) ** 2
    )
    return 2 * EARTH_R * math.asin(math.sqrt(s))


def cum_dist(points: list[tuple]) -> list[float]:
    """시작점부터의 누적 거리(m)."""
    cum = [0.0]
    for i in range(1, len(points)):
        cum.append(cum[-1] + haversine_m(points[i - 1], points[i]))
    return cum


def cum_gain(points: list[tuple], noise_m: float = 1.0) -> list[float]:
    """시작점부터의 누적 **상승** 고도(m).

    GPS 고도는 잡음이 커서 ±1m 수준의 흔들림이 계속 쌓인다. [noise_m] 미만의 변화는
    무시해 과대계상을 막는다. 고도가 없는 소스는 전부 0이 되므로 그대로 통과한다.
    """
    if not points or len(points[0]) < 3:
        return [0.0] * len(points)
    out = [0.0]
    ref = points[0][2]
    for i in range(1, len(points)):
        ele = points[i][2]
        if ele - ref > noise_m:
            out.append(out[-1] + (ele - ref))
            ref = ele
        else:
            out.append(out[-1])
            ref = min(ref, ele)
    return out


def _perp_m(p: tuple, a: tuple, b: tuple) -> float:
    """선분 a-b 에서 점 p 까지의 수직거리(m). 위경도를 국소 평면으로 근사한다."""
    lat0 = math.radians(a[0])
    mx = 111_320.0 * math.cos(lat0)  # 경도 1도의 m
    my = 110_540.0  # 위도 1도의 m
    ax, ay = a[1] * mx, a[0] * my
    bx, by = b[1] * mx, b[0] * my
    px, py = p[1] * mx, p[0] * my
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def simplify_indices(points: list[tuple], tolerance_m: float = 8.0) -> list[int]:
    """Douglas-Peucker 축약. **인덱스**를 돌려준다.

    인덱스를 돌려주는 이유 — 원본 해상도에서 계산한 `cum_dist`·`cum_gain` 값을
    같은 인덱스로 뽑아 축약 포인트에 실어야 하기 때문이다. 좌표만 축약하면
    고도 정보가 함께 뭉개진다.
    """
    n = len(points)
    if n <= 2:
        return list(range(n))

    keep = [False] * n
    keep[0] = keep[n - 1] = True
    stack = [(0, n - 1)]
    while stack:
        lo, hi = stack.pop()
        if hi - lo < 2:
            continue
        far_i, far_d = -1, 0.0
        for i in range(lo + 1, hi):
            d = _perp_m(points[i], points[lo], points[hi])
            if d > far_d:
                far_i, far_d = i, d
        if far_d > tolerance_m:
            keep[far_i] = True
            stack.append((lo, far_i))
            stack.append((far_i, hi))
    return [i for i, k in enumerate(keep) if k]
