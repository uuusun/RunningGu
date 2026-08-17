"""코스 소스 어댑터 인터페이스.

새 소스를 붙이려면 이 파일을 건드리지 않고 `sources/` 에 파일 하나를 추가한 뒤
[REGISTRY] 에 등록하면 된다. 어댑터가 할 일은 **좌표를 [RawCourse] 로 내놓는 것뿐**이고,
누적고도·축약·지역 재계산은 파이프라인이 공통으로 처리한다.

라이선스를 어댑터의 필수 필드로 둔 이유 — 소스마다 조건이 다르고, 그 조건이
**우리가 코스를 잘라도 되는지**를 결정하기 때문이다. 공공누리 4유형(변경금지)은
`buildRouteNear` 의 구간 잘라내기가 저촉될 수 있어 [derivable] 로 구분한다.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Iterator

from ..model import RawCourse


class CourseSource(ABC):
    #: `courses.json` 의 `source` 필드 값.
    key: str = ""
    #: 화면 하단 출처 문구에 쓴다. 공공누리·ODbL 모두 출처표시가 의무다.
    attribution: str = ""
    #: 라이선스 표기(사람이 읽는 용도).
    license: str = ""
    #: 구간을 잘라 파생 경로를 만들어도 되는가. 변경금지 조건이면 False.
    derivable: bool = True

    @abstractmethod
    def fetch(self) -> Iterator[RawCourse]:
        """원본을 읽어 코스를 하나씩 내놓는다."""
        raise NotImplementedError


#: 어댑터 등록부. `build_courses.py --sources` 가 여기서 찾는다.
REGISTRY: dict[str, type[CourseSource]] = {}


def register(cls: type[CourseSource]) -> type[CourseSource]:
    """어댑터 클래스에 붙이는 데코레이터."""
    if not cls.key:
        raise ValueError(f"{cls.__name__}: key 가 비었다")
    REGISTRY[cls.key] = cls
    return cls
