"""서울둘레길(서울시 열린데이터광장) 어댑터 — **라이선스 확인 전까지 비활성**.

데이터: "서울시 둘레길 선형 위치정보 (좌표계: WGS1984)" · SHP
        https://data.seoul.go.kr/dataList/OA-11986/S/1/datasetView.do

라이선스: **공공누리 제4유형 — 출처표시 + 상업적 이용금지 + 변경금지**

무엇이 문제인가 — 세 조건 중 **변경금지**가 우리 사용 방식에 걸린다.

  · 출처표시      → 화면 하단에 표기하면 해결된다.
  · 상업적 이용금지 → 공모전 출품용 무료 앱이라 저촉 가능성이 낮다.
  · 변경금지      → `buildRouteNear` 는 코스에서 목표거리만큼 **구간을 잘라** 왕복
                    경로를 만든다. 이건 원저작물의 변경에 해당할 소지가 크다.
                    유료·무료와 무관하며 무료라고 면제되지 않는다.

따라서 둘 중 하나가 정해지기 전에는 쓰지 않는다.

  (a) 서울시에 이용 문의 → 공모전 출품 목적으로 별도 허락을 받는다. ← 권장
  (b) 자르지 않고 코스 전체를 원본대로만 표시한다. 이 경우 '지역별' 탭에만 노출하고
      '내 주변'(목표거리 자르기)에서는 제외해야 하므로 `derivable = False` 로 둔다.

SHP 파싱에는 `pyshp` 가 필요하다: pip install pyshp
"""
from __future__ import annotations

from collections.abc import Iterator

from ..model import RawCourse
from .base import CourseSource, register


@register
class SeoulDulle(CourseSource):
    key = "seoul_dulle"
    attribution = "서울둘레길(서울특별시)"
    license = "공공누리 제4유형 — 출처표시 + 상업적이용금지 + 변경금지"
    #: 구간 잘라내기가 '변경'에 해당할 수 있어 기본 차단. 허락을 받으면 True 로 바꾼다.
    derivable = False

    def fetch(self) -> Iterator[RawCourse]:
        raise SystemExit(
            "seoul_dulle 는 라이선스(공공누리 4유형·변경금지) 확인 전까지 비활성이다.\n"
            "서울시에 이용 문의 후 이 어댑터를 구현하고 derivable 을 정하라.\n"
            "구현 시: shapefile.Reader 로 폴리라인을 읽고 각 shape 를 RawCourse 로 내보내면 된다.\n"
            "  · SHP 는 고도가 없다 → points 는 (lat, lng) 2원소로 두면 파이프라인이 0으로 채운다\n"
            "  · 좌표계가 WGS1984 인지 확인할 것(ITRF2000 버전이 따로 있다)"
        )
        yield  # pragma: no cover — 시그니처 유지용
