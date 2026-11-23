package com.runninggu.app.data.repository

import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.PoiCategory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * `FakeItineraryRepository` 픽스처가 실제 응답 계약과 맞는지 본다. (API 명세 §5-1)
 *
 * 이 테스트가 없어서 #66 이 `GenerateItineraryResponse` 에 필수 4필드를 넣었을 때
 * 픽스처가 따라가지 못한 것을 아무도 못 봤다 — S7 은 그동안 한 번도 뜨지 않았다.
 * 계약이 바뀌면 **여기가 먼저 깨져야 한다.**
 */
class FakeItineraryRepositoryTest {

    private fun request(
        event: EventType = EventType.TEN_K,
        days: Long = 2,
        hotel: HotelInput? = null,
    ) = GenerateItineraryRequest(
        contestId = 77L,
        startDate = LocalDate.of(2027, 4, 10),
        endDate = LocalDate.of(2027, 4, 10).plusDays(days),
        event = event,
        themes = listOf(PoiCategory.TOUR, PoiCategory.FOOD),
        hotel = hotel,
    )

    /** 회복 없는 종목 — NORMAL_FIXTURE 를 탄다. */
    @Test
    fun `10K 요청이 동선을 돌려준다`() = runTest {
        val result = FakeItineraryRepository.generate(request())

        assertTrue("일자가 비어 있으면 S7 이 빈 화면이 된다", result.days.isNotEmpty())
        assertTrue(result.days.all { it.blocks.isNotEmpty() })
    }

    /** 하프·풀 — RECOVERY_FIXTURE 를 탄다. 두 픽스처 모두 디코딩돼야 한다. */
    @Test
    fun `하프 요청이 회복 배지를 붙여 돌려준다`() = runTest {
        val result = FakeItineraryRepository.generate(request(event = EventType.HALF))

        assertTrue("하프는 회복 안내가 있어야 한다 (SPEC §5.5)", result.recovery != null)
    }

    /**
     * 회복 픽스처는 결정-74·75 골격을 흉내내야 한다 — 온천은 고정 블록이 아니라 취향 자리에만 오고,
     * 회복 골격은 **같은 일자의 비회복 골격보다** 선택 방문이 하나 적다(D-day 는 카페 슬롯이, D+N 은
     * 오전 관광이 없다 · SPEC §5.6-4). 옛 골격(`11:00 온천·회복` 고정)으로 되돌리면 이 테스트가 잡는다.
     */
    @Test
    fun `회복 픽스처는 온천을 고정 블록으로 두지 않는다`() = runTest {
        val hotel = HotelInput("하와이장", 37.90, 127.06)
        val result = FakeItineraryRepository.generate(request(event = EventType.HALF, hotel = hotel))

        val dday = result.days.first { it.off == 0 }
        val dplus = result.days.first { it.off == 1 }
        assertTrue("D-day 에 고정 온천 블록이 없어야 한다 (결정-74)", dday.blocks.none { it.catKey == BlockCategory.WELLNESS })
        assertEquals("회복 D-day 는 카페 고정 슬롯 없이 스타트·취향·회복 저녁 셋이다 (SPEC §5.6-4)", listOf("09:00", "14:30", "18:00"), dday.blocks.map { it.time })
        assertEquals("숙소를 골랐으니 마지막 날은 체크아웃 → 점심 → 취향이다", listOf("11:00", "12:30", "14:30"), dplus.blocks.map { it.time })
        assertEquals("웰니스를 고른 예시라 온천은 D+1 취향 자리에 온다", BlockCategory.WELLNESS, dplus.blocks.last().catKey)
        val checkout = dplus.blocks.first { it.catKey == BlockCategory.LODGING }.place
        assertEquals("체크아웃은 요청한 숙소로 한다", "하와이장", checkout?.name)
        assertEquals(37.90, checkout?.lat)
        assertEquals(127.06, checkout?.lng)
        assertEquals("요청 숙소에는 주소가 없으니 픽스처 주소를 남기지 않는다", "", checkout?.addr)
    }

    /**
     * 결정-75: `hotel=null` 이면 서버는 체크인·체크아웃을 포함한 자동 LODGING 블록을 하나도 만들지 않는다.
     * 가짜 저장소도 같은 계약을 지켜야 화면이 진짜 서버와 다른 모양을 보지 않는다.
     */
    @Test
    fun `숙소 없이 추천받으면 LODGING 블록이 하나도 없다`() = runTest {
        for (event in listOf(EventType.TEN_K, EventType.HALF)) {
            val result = FakeItineraryRepository.generate(request(event = event, hotel = null))

            val lodging = result.days.flatMap { it.blocks }.filter { it.catKey == BlockCategory.LODGING }
            assertTrue("$event · hotel=null 인데 LODGING 블록이 있다: ${lodging.map { it.title }}", lodging.isEmpty())
        }
    }

    /**
     * 스냅샷은 fixture 리터럴이 아니라 **요청**을 되비춰야 한다.
     *
     * §5-2 저장 요청이 이 값을 그대로 실어 보내므로, 여기가 어긋나면 화면은 멀쩡한데
     * 저장만 엉뚱한 대회·기간으로 나간다.
     */
    @Test
    fun `스냅샷이 요청을 그대로 되비춘다`() = runTest {
        val request = request(days = 2)

        val snapshot = FakeItineraryRepository.generate(request).request

        assertEquals(77L, snapshot.contestId)
        assertEquals("K10", snapshot.event)
        assertEquals("2027-04-10", snapshot.startDate)
        assertEquals("2027-04-12", snapshot.endDate)
        assertEquals(listOf("TOUR", "FOOD"), snapshot.themes)
    }

    /** 숙소를 고른 요청은 스냅샷에도 숙소가 실린다. (SPEC §4.9) */
    @Test
    fun `숙소를 고르면 스냅샷에 숙소가 실린다`() = runTest {
        val hotel = HotelInput(name = "시티 호텔", lat = 37.52, lng = 126.93)

        val snapshot = FakeItineraryRepository.generate(request(hotel = hotel)).request

        assertEquals("시티 호텔", snapshot.hotel?.name)
    }

    /** 숙소 없이 추천받으면 스냅샷의 숙소는 null 이다. (SPEC §4.9) */
    @Test
    fun `숙소 없이 추천받으면 스냅샷 숙소가 비어 있다`() = runTest {
        val snapshot = FakeItineraryRepository.generate(request()).request

        assertEquals(null, snapshot.hotel)
    }

    /** fixture 는 3일치라 더 짧은 기간은 잘라 쓴다. */
    @Test
    fun `요청 기간만큼만 일자를 돌려준다`() = runTest {
        val result = FakeItineraryRepository.generate(request(days = 1))

        assertEquals(2, result.days.size)
        assertEquals(LocalDate.of(2027, 4, 10), result.days.first().date)
    }
}
