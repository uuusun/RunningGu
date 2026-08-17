"""코스 소스 어댑터. 새 소스는 이 폴더에 파일을 추가하고 @register 를 붙인다."""

from . import durunubi, gpx_dir, seoul_dulle  # noqa: F401  등록 부수효과
from .base import REGISTRY, CourseSource, register  # noqa: F401
