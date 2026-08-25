package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.remote.dto.ItinerarySummaryDto
import com.runninggu.app.data.remote.dto.RecoveryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 저장 동선 목록 항목 매핑. (API 명세 §5-4 · SPEC §4.13)
 *
 * **표시 문자열을 매퍼가 만든다.** 화면이 날짜를 조립하면 같은 규칙이 여러 곳에 흩어지고
 * KST 해석이 매퍼 밖으로 샌다(AGENTS 2장-4).
 */
class ItinerarySummaryMapperTest {

    private fun dto(
        recovery: RecoveryDto? = null,
        active: Boolean = true,
        needsRegeneration: Boolean = false,
        event: String = "HALF",
    ) = ItinerarySummaryDto(
        id = 42L,
        title = "부산 2박 3일",
        contestId = 7L,
        contestName = "부산 마라톤",
        event = event,
        region = "부산",
        recovery = recovery,
        startDate = LocalDate.of(2026, 9, 5),
        endDate = LocalDate.of(2026, 9, 7),
        placeCount = 8,
        active = active,
        needsRegeneration = needsRegeneration,
    )

    @Test
    fun `종목은 서버 enum 이 아니라 사용자 라벨이다`() {
        // 카드에 `HALF`·`K10` 이 그대로 뜨면 계약 값이 사용자에게 노출된다(#181 리뷰)
        assertEquals("하프", dto(event = "HALF").toSavedItinerary().event)
        assertEquals("10K", dto(event = "K10").toSavedItinerary().event)
    }

    @Test
    fun `모르는 종목은 버리지 않고 그대로 둔다`() {
        // 서버가 종목을 늘렸을 때 빈 칸이 되면 그 사실이 화면에서 사라진다.
        // 낯선 글자가 보이는 편이 낫다 — `sourceTokensOf` 와 같은 판단이다.
        assertEquals("K3", dto(event = "K3").toSavedItinerary().event)
    }

    @Test
    fun `기간을 MM_DD 로 잇는다`() {
        assertEquals("09.05~09.07", dto().toSavedItinerary().period)
    }

    @Test
    fun `한 자리 월일에도 0을 채운다`() {
        // "9.5~9.7" 이면 카드 폭이 들쭉날쭉해진다
        val period = dto().copy(
            startDate = LocalDate.of(2026, 1, 3),
            endDate = LocalDate.of(2026, 1, 4),
        ).toSavedItinerary().period

        assertEquals("01.03~01.04", period)
    }

    @Test
    fun `id 는 문자열이다`() {
        // 화면·내비게이션 키가 문자열이다 (#52 리뷰)
        assertEquals("42", dto().toSavedItinerary().id)
    }

    @Test
    fun `대회 이름이 카드의 raceName 이다`() {
        assertEquals("부산 마라톤", dto().toSavedItinerary().raceName)
    }

    @Test
    fun `회복이 없으면 배지도 없다`() {
        assertNull(dto().toSavedItinerary().recoveryLabel)
        assertEquals(
            "D+1 회복 모드",
            dto(recovery = RecoveryDto("D+1 회복 모드", "온천")).toSavedItinerary().recoveryLabel,
        )
    }

    @Test
    fun `대회 변경과 비활성을 그대로 넘긴다`() {
        // 걸러 내거나 뒤집으면 화면이 §5-4 계약과 어긋난다
        val item = dto(active = false, needsRegeneration = true).toSavedItinerary()

        assertTrue(item.needsRegeneration)
        assertTrue(!item.active)
    }
}
