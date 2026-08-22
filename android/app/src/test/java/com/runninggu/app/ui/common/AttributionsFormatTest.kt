package com.runninggu.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 출처 표기 규칙. (SPEC §4.11-5 · 결정-44)
 *
 * 컴포저블은 단위 테스트로 못 보지만 **문구를 만드는 규칙**은 순수 함수라 여기서 고정한다.
 * 공공누리·ODbL 의무 표기라 앱이 순서나 문구를 바꾸면 라이선스 위반이 될 수 있어서,
 * "그대로 이어 붙이기만 한다" 를 테스트로 남긴다.
 */
class AttributionsFormatTest {

    @Test
    fun `배열 순서를 바꾸지 않는다`() {
        // 서버가 큐레이션 → OSM → 카카오 순으로 준다(§4-3). 앱이 정렬하면 그 순서가 깨진다
        val given = listOf(
            "두루누비 걷기길(한국관광공사)",
            "© OpenStreetMap contributors",
            "카카오 로컬",
        )

        assertEquals(
            "출처 · 두루누비 걷기길(한국관광공사) · © OpenStreetMap contributors · 카카오 로컬",
            attributionsText(given),
        )
    }

    @Test
    fun `문구를 다듬지 않는다`() {
        // 괄호·기호가 든 완성 문구가 그대로 나가야 한다
        val given = listOf("등산로·숲길(한국등산·트레킹지원센터)")

        assertEquals("출처 · 등산로·숲길(한국등산·트레킹지원센터)", attributionsText(given))
    }

    @Test
    fun `하나뿐이면 구분자를 안 붙인다`() {
        assertEquals("출처 · 카카오 로컬", attributionsText(listOf("카카오 로컬")))
    }

    @Test
    fun `비어 있으면 아무것도 그리지 않는다`() {
        // "출처 ·" 만 남으면 더 이상하다. 저장 코스는 실제로 빈 배열이 올 수 있다(결정-44)
        assertEquals(null, attributionsText(emptyList()))
    }
}
