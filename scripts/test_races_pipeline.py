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


def dist_row(distances, tokens="[]", **flags):
    """distances 컬럼이 있는 행. flags 는 has_full=True 처럼 준다."""
    r = {"distances": distances, "event_types": tokens}
    r.update({k: "true" for k, v in flags.items() if v})
    return r


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


class DistanceBucketTest(unittest.TestCase):
    """§5.4 ② distances 버킷 — 토큰만으로는 못 읽는 거리를 숫자로 읽는다 (이슈 #48)."""

    def test_15km_bucket_is_10k(self):
        # 토큰 "15km" 는 아무것도 못 잡지만 거리 15 는 ≥9 라 10K 다
        self.assertEqual(std_events(dist_row("[15.0]")), ["10K"])

    def test_40km_bucket_is_full(self):
        self.assertEqual(std_events(dist_row("[40.0]")), ["풀"])

    def test_bucket_boundaries(self):
        # ≥32 풀 · ≥18 하프 · ≥9 10K · 그 외 5K
        self.assertEqual(std_events(dist_row("[32.0]")), ["풀"])
        self.assertEqual(std_events(dist_row("[31.9]")), ["하프"])
        self.assertEqual(std_events(dist_row("[18.0]")), ["하프"])
        self.assertEqual(std_events(dist_row("[17.9]")), ["10K"])
        self.assertEqual(std_events(dist_row("[9.0]")), ["10K"])
        self.assertEqual(std_events(dist_row("[8.9]")), ["5K"])

    def test_zero_and_negative_are_ignored(self):
        self.assertEqual(std_events(dist_row("[0, -5]")), [])

    def test_broken_json_does_not_raise(self):
        self.assertEqual(std_events(dist_row("[not json")), [])


class UnionTest(unittest.TestCase):
    """§5.4 — 세 단계는 우선순위가 아니라 합집합이다. 앞 단계가 뒤를 가리면 안 된다."""

    def test_flag_does_not_hide_distances(self):
        # 무주 풀코스 실제 행: has_full 만 있고 거리에 하프·10K·5K 가 들어 있다
        self.assertEqual(
            std_events(dist_row("[42.195, 24, 12, 8, 4]", has_full=True)),
            ["풀", "하프", "10K", "5K"],
        )

    def test_five_k_flag_does_not_hide_full(self):
        # 엄격한 우선순위였다면 5K 하나로 줄어든다
        self.assertEqual(
            std_events(dist_row("[42.195, 21, 10, 5]", has_5k=True)),
            ["풀", "하프", "10K", "5K"],
        )

    def test_tokens_fill_what_distances_missed(self):
        # 거리로 못 채운 것만 토큰이 보강한다
        self.assertEqual(std_events(dist_row("[10.0]", tokens='["풀코스"]')), ["풀", "10K"])

    def test_union_order_is_fixed(self):
        # 어느 단계에서 왔든 순서는 [풀, 하프, 10K, 5K]
        self.assertEqual(
            std_events(dist_row("[5.0]", tokens='["하프"]', has_10k=True)),
            ["하프", "10K", "5K"],
        )


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
