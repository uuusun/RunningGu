package com.runninggu.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 걷기 스팟 필터. (SPEC §5.9)
 *
 * 넣은 데이터는 **카카오 로컬 API 를 실제로 호출해 받은 응답**이다(2026-08-14, 반경 3km).
 * 지어낸 값이 아니라서 규칙을 되돌리면 여기서 바로 걸린다.
 */
class WalkSpotFilterTest {

    private fun spot(name: String, category: String, distM: Int, addr: String = "서울") =
        WalkSpot(name = name, categoryName = category, address = addr, distanceM = distM)

    /**
     * 여의도 실측(37.5219, 126.9245). 샛강생태공원 부속 시설이 줄줄이 딸려 왔고,
     * 출입구·교차로·화장실 같은 것까지 "공원"으로 검색된다.
     */
    private val yeouido = listOf(
        spot("리나스 여의도공원점", "음식점 > 패스트푸드 > 샌드위치", 276),
        spot("여의도공원앞교차로", "교통,수송 > 도로시설 > 교차로", 321),
        spot("여의도공원 출입구1", "교통,수송 > 입출구", 393),
        spot("여의도공원 화장실", "가정,생활 > 화장실", 409),
        spot("샛강생태공원 반딧불이생태관", "여행 > 공원시설물", 413),
        spot("여의도샛강생태공원 방문자센터", "서비스,산업 > 관리,운영 > 공원관리운영", 414),
        spot("샛강생태공원 진입마루", "여행 > 공원시설물", 450),
        spot("샛강생태공원 야생화둔덕", "여행 > 공원시설물", 458),
        spot("샛강생태공원 관찰마루", "여행 > 공원시설물", 464),
        spot("여의도공원 인라인스케이트장", "스포츠,레저 > 스케이트 > 인라인스케이트장", 471),
        spot("샛강생태공원 조류관찰대", "여행 > 공원시설물", 471),
        spot("여의도공원 태극기게양대", "여행 > 공원시설물", 473),
        spot("샛강생태공원", "여행 > 공원 > 도시근린공원", 479),
        spot("여의도공원", "여행 > 공원 > 도시근린공원", 506),
        spot("샛강생태공원 그늘마루", "여행 > 공원시설물", 515),
        spot("앙카라공원", "여행 > 공원 > 도시근린공원", 712),
        spot("여의도한강공원", "여행 > 공원 > 도시근린공원", 1063),
        spot("여의도생태순환길", "여행 > 관광,명소 > 둘레길", 1118),
    )

    @Test
    fun `공원 안 부속 시설은 걸러낸다`() {
        val got = WalkSpotFilter.filter(yeouido).map { it.name }
        assertFalse("게양대가 남으면 안 된다", got.any { it.contains("태극기게양대") })
        assertFalse("생태관이 남으면 안 된다", got.any { it.contains("반딧불이생태관") })
        assertFalse("방문자센터가 남으면 안 된다", got.any { it.contains("방문자센터") })
    }

    @Test
    fun `같은 공원의 하위 지점은 한 곳으로 묶고 대표는 공원 이름이다`() {
        val got = WalkSpotFilter.filter(yeouido).map { it.name }
        assertEquals(
            "샛강생태공원 계열은 정확히 한 건만 남아야 한다",
            1, got.count { it.startsWith("샛강생태공원") },
        )
        assertTrue("대표는 하위 지점이 아니라 공원 자체여야 한다", "샛강생태공원" in got)
    }

    @Test
    fun `걸러낸 자리에 진짜 뛸 만한 곳이 올라온다`() {
        val got = WalkSpotFilter.filter(yeouido).map { it.name }
        // 기존 규칙에서는 부속 시설에 밀려 목록에 못 들어오던 것들
        assertTrue("여의도공원" in got)
        assertTrue("여의도한강공원" in got)
        assertTrue("여의도생태순환길" in got)
    }

    @Test
    fun `동네 어린이공원은 제외한다`() {
        // 강남역 실측 — 8칸 중 7칸이 이런 것들이었다
        val gangnam = listOf(
            spot("역삼까치공원", "여행 > 공원 > 도시근린공원", 286),
            spot("누리숲어린이공원", "여행 > 공원 > 도시근린공원", 722),
            spot("역삼개나리공원", "여행 > 공원 > 도시근린공원", 744),
            spot("새싹어린이공원", "여행 > 공원", 900),
            spot("매봉산", "여행 > 관광,명소 > 산", 1662),
        )
        val got = WalkSpotFilter.filter(gangnam).map { it.name }
        assertFalse("어린이공원은 빠져야 한다", got.any { it.contains("어린이공원") })
        assertTrue("일반 근린공원은 남는다", "역삼까치공원" in got)
        assertTrue("산도 걷기 대상이다", "매봉산" in got)
    }

    @Test
    fun `하천은 러닝에 좋아 그대로 통과한다`() {
        // 수원역 실측 — 개선 후 하천 3곳이 올라왔다
        val suwon = listOf(
            spot("매산천", "여행 > 관광,명소 > 하천", 789, "경기"),
            spot("수원천", "여행 > 관광,명소 > 하천", 1118, "경기"),
            spot("서호천", "여행 > 관광,명소 > 하천", 1261, "경기"),
        )
        val got = WalkSpotFilter.filter(suwon).map { it.name }
        assertEquals(listOf("매산천", "수원천", "서호천"), got)
    }

    @Test
    fun `걷는 곳이 아닌 하위 시설은 이름으로 걸러낸다`() {
        val raw = listOf(
            spot("한강공원 공중화장실", "여행 > 관광,명소 > 공원", 100),
            spot("올림픽공원 주차장", "여행 > 관광,명소 > 공원", 120),
            spot("서울숲", "여행 > 관광,명소 > 공원 > 도시근린공원", 300),
        )
        assertEquals(listOf("서울숲"), WalkSpotFilter.filter(raw).map { it.name })
    }

    @Test
    fun `이름과 주소가 같으면 중복이다`() {
        val raw = listOf(
            spot("청계천", "여행 > 관광,명소 > 하천", 322, "서울 중구"),
            spot("청계천", "여행 > 관광,명소 > 하천", 322, "서울 중구"),
        )
        assertEquals(1, WalkSpotFilter.filter(raw).size)
    }

    @Test
    fun `거리순으로 최대 12곳까지만 준다`() {
        val raw = (1..20).map { spot("공원$it", "여행 > 관광,명소 > 공원", it * 100) }
        val got = WalkSpotFilter.filter(raw)
        assertEquals(WalkSpotFilter.LIMIT, got.size)
        assertEquals(100, got.first().distanceM)
        assertTrue("거리 오름차순", got.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })
    }

    @Test
    fun `조회 조건이 SPEC 과 같다`() {
        assertEquals(
            listOf("공원", "산책로", "둘레길", "하천", "한강공원", "생태공원"),
            WalkSpotFilter.QUERIES,
        )
        assertEquals(3000, WalkSpotFilter.RADIUS_M)
        assertEquals(15, WalkSpotFilter.PAGE_SIZE)
        assertEquals(12, WalkSpotFilter.LIMIT)
    }

    @Test
    fun `화면에 쓰는 짧은 분류는 계층의 마지막 조각이다`() {
        assertEquals("도시근린공원", spot("여의도공원", "여행 > 관광,명소 > 공원 > 도시근린공원", 1).category)
        assertEquals("하천", spot("청계천", "여행 > 관광,명소 > 하천", 1).category)
    }
}
