package com.runninggu.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 도메인 규칙이 SPEC §5 표와 한 글자도 다르지 않은지 지킨다.
 *
 * 이 값들은 "포팅 시 변경 금지"라 테스트가 곧 계약서다.
 */
class DomainRulesTest {

    // ── §5.4 종목 표준화 ────────────────────────────────────────

    @Test
    fun `종목 표기를 표준 4종으로 정규화한다`() {
        assertEquals(EventType.FULL, stdEvent("풀코스"))
        assertEquals(EventType.FULL, stdEvent("Full Marathon"))
        assertEquals(EventType.FULL, stdEvent("42.195km"))
        assertEquals(EventType.HALF, stdEvent("하프"))
        assertEquals(EventType.HALF, stdEvent("half"))
        assertEquals(EventType.HALF, stdEvent("21km"))
        assertEquals(EventType.TEN_K, stdEvent("10K"))
        assertEquals(EventType.TEN_K, stdEvent("10km"))
        // 알 수 없는 표기는 5K 로 떨어진다
        assertEquals(EventType.FIVE_K, stdEvent("걷기"))
        assertEquals(EventType.FIVE_K, stdEvent(null))
    }

    @Test
    fun `표준 종목 목록은 중복을 없애고 풀 하프 10K 5K 순이다`() {
        val got = stdEvents(listOf("10k", "풀", "5k", "하프", "10K"))
        assertEquals(listOf(EventType.FULL, EventType.HALF, EventType.TEN_K, EventType.FIVE_K), got)
    }

    @Test
    fun `거리 버킷 경계는 32 18 9 다`() {
        assertEquals(EventType.FULL, stdEventKm(42.195))
        assertEquals(EventType.FULL, stdEventKm(32.0))
        assertEquals(EventType.HALF, stdEventKm(31.9))
        assertEquals(EventType.HALF, stdEventKm(18.0))
        assertEquals(EventType.TEN_K, stdEventKm(17.9))
        assertEquals(EventType.TEN_K, stdEventKm(9.0))
        assertEquals(EventType.FIVE_K, stdEventKm(8.9))
        assertNull(stdEventKm(null))
    }

    @Test
    fun `종목 추출은 플래그가 거리보다 우선한다`() {
        // 플래그가 있으면 거리는 보지 않는다
        val byFlag = eventsFromRace(
            flags = EventFlags(half = true),
            distancesKm = listOf(42.0),
        )
        assertEquals(listOf(EventType.HALF), byFlag)

        // 플래그가 비면 거리 버킷
        val byDistance = eventsFromRace(
            flags = EventFlags(),
            distancesKm = listOf(42.195, 21.0),
        )
        assertEquals(listOf(EventType.FULL, EventType.HALF), byDistance)

        // 둘 다 없으면 토큰
        val byToken = eventsFromRace(eventTypes = listOf("10K"))
        assertEquals(listOf(EventType.TEN_K), byToken)
    }

    // ── §5.1 회복 룰 ───────────────────────────────────────────

    @Test
    fun `회복 룰 값이 SPEC 표와 같다`() {
        assertEquals(RecoveryRule(8, false, "거의 정상", "완주 후 오후부터 자유 관광", "일반 관광 자유"), Recovery[EventType.FIVE_K])
        assertEquals(RecoveryRule(8, false, "낮은 피로", "완주 후 가벼운 관광·축제", "일반 관광"), Recovery[EventType.TEN_K])
        assertEquals(RecoveryRule(5, true, "중등도 피로", "완주 후 온천·휴식 권장", "온천+짧은 산책(고강도 제외)"), Recovery[EventType.HALF])
        assertEquals(RecoveryRule(3, true, "고강도 회복 필요", "완주 후 회복 집중, 도보 최소", "스파·온천 중심, 도보 최소"), Recovery[EventType.FULL])
    }

    @Test
    fun `고강도 제외는 하프와 풀에만 붙는다`() {
        assertTrue(Recovery[EventType.HALF].noHard)
        assertTrue(Recovery[EventType.FULL].noHard)
        assertTrue(!Recovery[EventType.FIVE_K].noHard)
        assertTrue(!Recovery[EventType.TEN_K].noHard)
    }

    @Test
    fun `러닝코스 목표 거리 기본값은 walk 와 5 중 작은 쪽이다`() {
        assertEquals(5.0, Recovery.defaultCourseTargetKm(EventType.FIVE_K), 0.0) // min(8,5)
        assertEquals(5.0, Recovery.defaultCourseTargetKm(EventType.TEN_K), 0.0)  // min(8,5)
        assertEquals(5.0, Recovery.defaultCourseTargetKm(EventType.HALF), 0.0)   // min(5,5)
        assertEquals(3.0, Recovery.defaultCourseTargetKm(EventType.FULL), 0.0)   // min(3,5)
    }

    // ── §5.6-6 회복 배지 ───────────────────────────────────────

    @Test
    fun `회복 배지는 하프 풀에만 뜬다`() {
        assertNull(recoveryBadgeOf(EventType.FIVE_K, listOf(-1, 0, 1)))
        assertNull(recoveryBadgeOf(EventType.TEN_K, listOf(-1, 0, 1)))
        assertNotNull(recoveryBadgeOf(EventType.HALF, listOf(-1, 0, 1)))
        assertNotNull(recoveryBadgeOf(EventType.FULL, listOf(0)))
    }

    @Test
    fun `D플러스 일자가 있으면 그 라벨을 없으면 D-day 를 쓴다`() {
        val withPlus = recoveryBadgeOf(EventType.HALF, listOf(-1, 0, 1))!!
        assertEquals("D+1 회복 모드", withPlus.label)
        assertEquals("온천+짧은 산책(고강도 제외)", withPlus.text)

        val onlyDday = recoveryBadgeOf(EventType.HALF, listOf(-1, 0))!!
        assertEquals("D-day 회복 모드", onlyDday.label)
        assertEquals("완주 후 온천·휴식 권장", onlyDday.text)
    }

    // ── §5.2 일정 패턴 ─────────────────────────────────────────

    @Test
    fun `일정 패턴 오프셋이 SPEC 과 같다`() {
        val race = LocalDate.of(2026, 8, 22)
        assertEquals(LocalDate.of(2026, 8, 21)..LocalDate.of(2026, 8, 22), TripPattern.PRE.rangeOf(race))
        assertEquals(LocalDate.of(2026, 8, 22)..LocalDate.of(2026, 8, 23), TripPattern.POST.rangeOf(race))
        assertEquals(LocalDate.of(2026, 8, 21)..LocalDate.of(2026, 8, 23), TripPattern.AROUND.rangeOf(race))
        assertEquals(LocalDate.of(2026, 8, 22)..LocalDate.of(2026, 8, 22), TripPattern.DAY.rangeOf(race))
        assertNull(TripPattern.CUSTOM.rangeOf(race))
    }

    @Test
    fun `기본 일정 패턴은 전후로다`() {
        assertEquals(TripPattern.AROUND, TripPattern.DEFAULT)
    }

    // ── §5.3 카테고리 ──────────────────────────────────────────

    @Test
    fun `숙소는 취향 칩에 노출되지 않는다`() {
        assertTrue(PoiCategory.LODGING !in PoiCategory.selectable)
        assertEquals(6, PoiCategory.selectable.size)
    }

    @Test
    fun `카카오 조회 방식이 카테고리마다 정해져 있다`() {
        assertEquals("AT4", PoiCategory.TOUR.code)
        assertEquals("FD6", PoiCategory.FOOD.code)
        assertEquals("CE7", PoiCategory.CAFE.code)
        assertEquals("AD5", PoiCategory.LODGING.code)
        assertEquals("온천 스파 사우나 찜질방", PoiCategory.WELLNESS.keyword)
        assertEquals("둘레길 공원 산책로 수목원", PoiCategory.NATURE.keyword)
        assertEquals("박물관 유적지 문화재", PoiCategory.HISTORY.keyword)
    }

    @Test
    fun `취향 기본값은 관광지와 맛집이다`() {
        assertEquals(listOf(PoiCategory.TOUR, PoiCategory.FOOD), PoiCategory.DEFAULT_THEMES)
    }

    // ── §6.6 날짜 ─────────────────────────────────────────────

    @Test
    fun `오프셋 라벨`() {
        assertEquals("D-1", offLabel(-1))
        assertEquals("D-day", offLabel(0))
        assertEquals("D+1", offLabel(1))
        assertEquals("D+2", offLabel(2))
    }

    @Test
    fun `날짜 범위는 양끝을 포함한다`() {
        val days = dateRange(LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 23))
        assertEquals(3, days.size)
        assertEquals(LocalDate.of(2026, 8, 21), days.first())
        assertEquals(LocalDate.of(2026, 8, 23), days.last())
        // 뒤집힌 범위는 빈 목록
        assertTrue(dateRange(LocalDate.of(2026, 8, 23), LocalDate.of(2026, 8, 21)).isEmpty())
    }

    @Test
    fun `요일과 짧은 날짜 표기`() {
        val d = LocalDate.of(2026, 8, 22) // 토요일
        assertEquals("토", dowKo(d))
        assertEquals("08.22 토", shortKo(d))
        assertEquals("08.21 ~ 08.23", tripRangeLabel(LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 23)))
    }

    @Test
    fun `D-day 는 오늘부터 대회일까지 남은 일수다`() {
        val today = LocalDate.of(2026, 8, 1)
        assertEquals(21, dDay(LocalDate.of(2026, 8, 22), today))
        assertEquals(0, dDay(today, today))
        assertEquals(-1, dDay(LocalDate.of(2026, 7, 31), today))
    }

    @Test
    fun `D-day 표기는 남은 날은 D빼기 지난 날은 D더하기다`() {
        val today = LocalDate.of(2026, 8, 1)
        assertEquals("D-21", dDayLabel(LocalDate.of(2026, 8, 22), today))
        assertEquals("D-1", dDayLabel(LocalDate.of(2026, 8, 2), today))
        assertEquals("D-day", dDayLabel(today, today))
        assertEquals("D+1", dDayLabel(LocalDate.of(2026, 7, 31), today))
        assertEquals("D+2", dDayLabel(LocalDate.of(2026, 7, 30), today))
    }

    @Test
    fun `오늘은 기기 타임존이 아니라 KST 기준이다`() {
        // 기기가 UTC 여도 KST 로 계산해야 한다. UTC 09-00 이전이면 한국은 이미 다음 날이다.
        // (SPEC §6.6 — 버그 1순위)
        assertEquals(ZoneId.of("Asia/Seoul"), KST)
        assertEquals(LocalDate.now(KST), today())
    }
}
