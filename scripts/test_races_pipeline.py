#!/usr/bin/env python3
"""대회 데이터 파이프라인 회귀 테스트 (PR #41 리뷰 반영).

실제 적재 결과를 바꾼 종목 정규식·NFC 수정이 되돌아가지 않게 잡는다.

사용: python -m unittest test_races_pipeline (scripts/ 에서)
"""
import unittest
import unicodedata

from build_races_json import norm_name, std_events


def row(tokens):
    """has_* 플래그 없이 event_types 토큰만 있는 행."""
    return {"event_types": tokens}


class StdEventsTest(unittest.TestCase):
    """§5.4 종목 표준화 — 'Nkm' 토큰이 5K/10K 로 오인되면 안 된다."""

    def test_15km_is_not_5k(self):
        self.assertEqual(std_events(row('["15km"]')), [])

    def test_4_5km_is_not_5k(self):
        self.assertEqual(std_events(row('["4.5km"]')), [])

    def test_110km_is_not_10k(self):
        self.assertEqual(std_events(row('["110km"]')), [])

    def test_10km_is_10k(self):
        self.assertEqual(std_events(row('["10km"]')), ["10K"])

    def test_5km_is_5k(self):
        self.assertEqual(std_events(row('["5km"]')), ["5K"])

    def test_full_half_tokens(self):
        self.assertEqual(std_events(row('["풀코스", "하프"]')), ["풀", "하프"])

    def test_flags_win_over_tokens(self):
        self.assertEqual(std_events({"has_full": "true", "event_types": '["15km"]'}), ["풀"])

    def test_order_is_fixed(self):
        self.assertEqual(std_events(row('["5km", "10km", "half", "full"]')), ["풀", "하프", "10K", "5K"])


class NormNameTest(unittest.TestCase):
    """병합 그룹 키 — NFC/NFD 가 같은 키를 만들어야 한다."""

    def test_nfd_equals_nfc(self):
        nfc = "2026 한글런"
        nfd = unicodedata.normalize("NFD", nfc)
        self.assertNotEqual(nfc, nfd)  # 전제: 실제로 다른 바이트열이다
        self.assertEqual(norm_name(nfc), norm_name(nfd))

    def test_spacing_symbols_case(self):
        self.assertEqual(norm_name("JUST RUN10 대전"), norm_name("just-run10 대전!"))


if __name__ == "__main__":
    unittest.main()
