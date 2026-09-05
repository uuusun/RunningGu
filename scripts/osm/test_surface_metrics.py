#!/usr/bin/env python3
"""노면·환경·경사 지표. (이슈 #224)

## 왜 더했나

`road_class` 만 보면 **대로변 보도와 한강 산책로가 둘 다 `footway` 로 1.00 점**이다.
실제로 뛰면 완전히 다른 길인데 이름표가 같아서 점수가 같다. #224 에 적어 둔
"길 품질을 길 종류 이름표만 보고 매기고 있다" 가 이것이고, 그 상태로는 점수를
올려도 실제로 좋은 길을 고른 것인지 확신할 수 없다.

`surface`·`road_environment`·`average_slope` 는 `graphhopper.yml` 의
`graph.encoded_values` 에 **이미 올라가 있어** 요청만 하면 온다. 새로 import 할
필요가 없다.

## 망가뜨리면 이것만 실패한다

실제로 돌려 보고 적는다(2026-09-05).

```
_by_distance 를 구간 개수 세기로 되돌린다
  → 노면은_구간_개수가_아니라_실제_거리로_잰다              FAILED
    다리와_터널을_따로_센다                                FAILED   (같은 집계를 쓴다)

_share 가 분모 0 일 때 0.0 을 돌려준다
  → 노면을_못_받으면_0퍼센트가_아니라_모른다                FAILED

_steep_share 에서 abs() 를 뺀다
  → 내리막도_경사로_센다                                   FAILED

details 요청에서 surface 를 뺀다
  → 요청에_노면과_환경과_경사를_함께_싣는다                 FAILED
```
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))

import roundtrip


class Response:
    def __init__(self, status_code: int, payload: dict):
        self.status_code = status_code
        self._payload = payload

    def json(self) -> dict:
        return self._payload


def path(details: dict, points: list[list[float]] | None = None) -> dict:
    """좌표 5개짜리 경로. 구간은 4개다."""
    pts = points or [
        [127.0, 37.50, 10.0],
        [127.0, 37.51, 10.0],
        [127.0, 37.52, 10.0],
        [127.0, 37.53, 10.0],
        [127.0, 37.54, 10.0],
    ]
    return {
        "distance": 4400.0,
        "points": {"coordinates": pts},
        "instructions": [],
        "details": details,
    }


class SurfaceMetricsTest(unittest.TestCase):

    # ── 거리 가중 ──────────────────────────────────────────────

    def test_노면은_구간_개수가_아니라_실제_거리로_잰다(self) -> None:
        # 흙길이 구간 1개(3칸)이고 아스팔트가 구간 1개(1칸)다. 개수로 세면 50:50 이지만
        # 실제 거리는 3:1 이다 — 좌표가 촘촘한 굽은 길이 부풀던 자리와 같은 함정이다.
        parsed = roundtrip._parse(path({
            "road_class": [[0, 4, "footway"]],
            "surface": [[0, 3, "ground"], [3, 4, "asphalt"]],
        }), seed=0)

        self.assertAlmostEqual(parsed["soft"], 75.0, places=1)
        self.assertAlmostEqual(parsed["paved"], 25.0, places=1)

    # ── 모른다 vs 0% ───────────────────────────────────────────

    def test_노면을_못_받으면_0퍼센트가_아니라_모른다(self) -> None:
        # 서버가 details 를 안 주는 경우다. 0.0 으로 적으면 표에서
        # "포장이 하나도 없는 흙길" 로 읽힌다 — 안 잰 것과 0 인 것은 다르다.
        parsed = roundtrip._parse(path({"road_class": [[0, 4, "footway"]]}), seed=0)

        self.assertIsNone(parsed["soft"])
        self.assertIsNone(parsed["paved"])
        self.assertIsNone(parsed["steep"])
        self.assertIsNone(parsed["tunnel"])
        # 기존 지표는 그대로 나와야 한다 — 새 지표가 없다고 측정이 죽으면 안 된다
        self.assertAlmostEqual(parsed["good"], 100.0, places=1)
        self.assertAlmostEqual(parsed["qual"], 100.0, places=1)

    def test_노면이_전부_모르는_값이면_비율은_0이_아니라_잰_만큼만_센다(self) -> None:
        # unknown 도 잰 구간이다. 분모에는 들어가고 soft/paved 어느 쪽도 아니다
        parsed = roundtrip._parse(path({
            "road_class": [[0, 4, "footway"]],
            "surface": [[0, 2, "unknown"], [2, 4, "asphalt"]],
        }), seed=0)

        self.assertAlmostEqual(parsed["soft"], 0.0, places=1)
        self.assertAlmostEqual(parsed["paved"], 50.0, places=1)

    # ── 환경 ───────────────────────────────────────────────────

    def test_다리와_터널을_따로_센다(self) -> None:
        # 한강 산책로는 다리 밑을 지나고, 대로변 보도는 지하차도를 지난다.
        # 둘 다 footway 지만 뛰는 느낌이 다르다.
        parsed = roundtrip._parse(path({
            "road_class": [[0, 4, "footway"]],
            "road_environment": [[0, 1, "bridge"], [1, 2, "tunnel"], [2, 4, "road"]],
        }), seed=0)

        self.assertAlmostEqual(parsed["bridge"], 25.0, places=1)
        self.assertAlmostEqual(parsed["tunnel"], 25.0, places=1)

    # ── 경사 ───────────────────────────────────────────────────

    def test_내리막도_경사로_센다(self) -> None:
        # 왕복 경로라 오르막만 세면 같은 언덕이 절반으로 보인다.
        parsed = roundtrip._parse(path({
            "road_class": [[0, 4, "footway"]],
            "average_slope": [[0, 1, 8.0], [1, 2, -8.0], [2, 4, 1.0]],
        }), seed=0)

        self.assertAlmostEqual(parsed["steep"], 50.0, places=1)

    def test_기준_미만_경사는_안_센다(self) -> None:
        parsed = roundtrip._parse(path({
            "road_class": [[0, 4, "footway"]],
            "average_slope": [[0, 4, roundtrip.STEEP_SLOPE_PCT - 0.1]],
        }), seed=0)

        self.assertAlmostEqual(parsed["steep"], 0.0, places=1)

    # ── 요청 ───────────────────────────────────────────────────

    def test_요청에_노면과_환경과_경사를_함께_싣는다(self) -> None:
        # 넷 다 graphhopper.yml 의 graph.encoded_values 에 있어 재import 가 필요 없다.
        captured: dict = {}

        def fake_get(url, params=None, timeout=None):
            captured.update(params or {})
            return Response(200, {"paths": []})

        with patch.object(roundtrip.requests, "get", side_effect=fake_get):
            roundtrip.route_observation(37.5, 127.0, 5.0, 0, "run")

        self.assertEqual(
            captured["details"],
            ["road_class", "surface", "road_environment", "average_slope"],
        )

    # ── 기존 지표 보존 ──────────────────────────────────────────

    def test_새_지표를_더해도_기존_품질_점수는_그대로다(self) -> None:
        # 골목 절반 + 보행로 절반이면 (0.50 + 1.00) / 2 = 0.75 → 75점
        parsed = roundtrip._parse(path({
            "road_class": [[0, 2, "residential"], [2, 4, "footway"]],
            "surface": [[0, 4, "asphalt"]],
        }), seed=0)

        self.assertAlmostEqual(parsed["qual"], 75.0, places=1)
        self.assertAlmostEqual(parsed["alley"], 50.0, places=1)
        self.assertAlmostEqual(parsed["good"], 50.0, places=1)
        self.assertAlmostEqual(parsed["paved"], 100.0, places=1)


if __name__ == "__main__":
    unittest.main()
