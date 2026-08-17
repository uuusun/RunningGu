"""코스 정규화 스키마.

어댑터는 **좌표와 메타만** 내놓고(`RawCourse`), 누적고도·축약·지역 재계산 같은
공통 가공은 파이프라인이 한다(`normalize`). 그래야 소스를 추가할 때 어댑터 파일
하나만 쓰면 된다.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from .geo import cum_dist, cum_gain, simplify_indices

# 두루누비 crsLevel 과 같은 척도. 1 하 · 2 중 · 3 상.
LEVEL_LABEL = {1: "쉬움", 2: "보통", 3: "어려움"}

REGIONS = [
    "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
    "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주",
]


@dataclass
class RawCourse:
    """어댑터가 내놓는 중간 형태. 가공 전이다."""

    id: str
    name: str
    #: [(lat, lng)] 또는 [(lat, lng, ele)]. 고도는 있으면 넣고 없으면 생략한다.
    points: list[tuple]
    sido: str = ""
    sigun: str = ""
    level: int | None = None
    cycle: str = ""
    summary: str = ""
    #: 원본이 알려준 총거리(km). 좌표에서 잰 값과 다르면 검증 로그에 남긴다.
    declared_km: float | None = None
    #: 메타를 어디서 얻었는지. 두루누비는 `API_GPX` / `GPX_ONLY` 를 구분한다(SPEC §8.4).
    data_source: str = ""


@dataclass
class Course:
    """`courses.json` 한 줄. 앱·서버가 함께 쓰는 계약이다."""

    id: str
    source: str
    name: str
    sido: str
    sigun: str
    dist_km: float
    gain_m: int
    level: int
    level_label: str
    cycle: str
    summary: str
    #: `API_GPX`(API 메타 + GPX) 또는 `GPX_ONLY`(API 에서 사라졌지만 경로는 보유).
    data_source: str = ""
    #: [lat, lng, ele, cumGainM] — ele·cumGainM 은 고도 없는 소스면 0.
    points: list[list[float]] = field(default_factory=list)

    def to_json(self) -> dict:
        return {
            "id": self.id,
            "source": self.source,
            "dataSource": self.data_source,
            "name": self.name,
            "sido": self.sido,
            "sigun": self.sigun,
            "distKm": self.dist_km,
            "gainM": self.gain_m,
            "level": self.level,
            "levelLabel": self.level_label,
            "cycle": self.cycle,
            "summary": self.summary,
            "points": self.points,
        }


def grade_from_gain(gain_m: float, dist_km: float) -> int:
    """상승고도(m/km)로 난이도를 매긴다.

    원본 `level` 을 쓰지 않는 이유 — 그건 코스 **전체**(15~26km)의 난이도인데
    우리는 2.5km 조각을 잘라 쓴다. 해파랑길 1코스(level=2)를 2.5km로 쪼개보면
    조각별 상승고도가 36m~309m 로 9배 차이났다. 전체 등급을 조각에 붙이면 거짓말이 된다.

    아래 경계는 출발선이다. 실데이터 분포를 보고 보정할 것.
    """
    if dist_km <= 0:
        return 2
    per_km = gain_m / dist_km
    if per_km < 15:
        return 1
    if per_km < 50:
        return 2
    return 3


def normalize(raw: RawCourse, source: str, tolerance_m: float = 8.0) -> Course:
    """원본 해상도로 거리·고도를 재고, 그 값을 실은 채 좌표를 축약한다."""
    pts = raw.points
    if len(pts) < 2:
        raise ValueError(f"{raw.id}: 좌표가 2개 미만이다")

    dist = cum_dist(pts)
    gain = cum_gain(pts)
    total_km = round(dist[-1] / 1000, 1)
    total_gain = int(round(gain[-1]))

    keep = simplify_indices(pts, tolerance_m)
    points = [
        [
            round(pts[i][0], 6),
            round(pts[i][1], 6),
            round(pts[i][2], 1) if len(pts[i]) >= 3 else 0.0,
            round(gain[i], 1),
        ]
        for i in keep
    ]

    level = raw.level or grade_from_gain(total_gain, total_km)
    return Course(
        id=raw.id,
        source=source,
        name=raw.name,
        sido=raw.sido,
        sigun=raw.sigun,
        dist_km=total_km,
        gain_m=total_gain,
        level=level,
        level_label=LEVEL_LABEL.get(level, "보통"),
        cycle=raw.cycle,
        summary=raw.summary,
        data_source=raw.data_source,
        points=points,
    )
