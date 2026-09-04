package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.model.CourseDataSource
import com.runninggu.app.data.model.Difficulty
import com.runninggu.app.data.remote.dto.CourseDetailDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * `CourseDetailDto` → `CuratedCourseDetail`. (#280 계약 · #286 리뷰)
 *
 * **매퍼 자체를 보는 테스트가 없었다.** `RemoteCourseRepositoryTest` 는 저장소 경로를
 * 보지 매핑을 보지 않아서, 계약과 다르게 옮겨도 아무도 못 잡았다(AGENTS 3장 — `data/`
 * 를 바꾸면 매퍼 단위 테스트).
 *
 * ## 망가뜨리면 이것만 실패한다
 *
 * - `path = Polyline.decode(pathPolyline)` 를 `emptyList()` 로 → `폴리라인을 풀어 좌표를 채운다` 만
 * - `pathPolyline` 을 안 들고 가면 → `인코딩 원문을 그대로 들고 간다` 만
 * - `difficultyOf` 를 빼면 → `전체 코스 등급을 그대로 옮긴다 - HARD 포함` 만
 */
class CourseDetailMapperTest {

    // 실제 E5 문자열. `Polyline.decode` 가 푸는 값이라 좌표를 손으로 적지 않는다.
    private val encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    private fun dto(
        difficulty: String? = "NORMAL",
        dataSource: String? = "API_GPX",
        syncedAt: Instant? = Instant.parse("2026-08-20T00:00:00Z"),
        polyline: String = encoded,
        elevation: List<Int> = listOf(12, 14, 19),
    ) = CourseDetailDto(
        courseId = "T_CRS_MNG0000005117",
        courseName = "해파랑길 1코스",
        sido = "부산",
        sigun = "남구",
        distanceKm = 17.8,
        difficulty = difficulty,
        gainM = 312,
        durationMin = 162,
        dataSource = dataSource,
        syncedAt = syncedAt,
        pathPolyline = polyline,
        elevationProfileM = elevation,
        attributions = listOf("두루누비 걷기길(한국관광공사)"),
    )

    @Test
    fun `목록 필드를 그대로 옮긴다`() {
        val d = dto().toDomain()
        assertEquals("T_CRS_MNG0000005117", d.courseId)
        assertEquals("해파랑길 1코스", d.courseName)
        assertEquals("부산", d.sido)
        assertEquals("남구", d.sigun)
        assertEquals(17.8, d.distanceKm, 0.0)
        assertEquals(312, d.gainM)
        assertEquals(162, d.durationMin)
        assertEquals(CourseDataSource.API_GPX, d.dataSource)
        assertEquals(listOf(12, 14, 19), d.elevationProfileM)
        assertEquals(listOf("두루누비 걷기길(한국관광공사)"), d.attributions)
    }

    // 화면은 좌표를 쓴다. 매퍼가 안 풀면 지도가 "경로를 그릴 수 없어요" 로 떨어진다.
    @Test
    fun `폴리라인을 풀어 좌표를 채운다`() {
        val path = dto().toDomain().path
        assertTrue("점이 2개 이상이어야 선이 된다", path.size >= 2)
    }

    // 서버에 되돌려 보낼 일이 있으면 원문을 그대로 쓴다 — 풀었다 다시 묶으면 값이 달라진다(#62).
    @Test
    fun `인코딩 원문을 그대로 들고 간다`() {
        assertEquals(encoded, dto().toDomain().pathPolyline)
    }

    // 원본 전체 등급이라 near 의 구간 등급과 달라도 정상이고 HARD 도 온다 (§4.11-b).
    @Test
    fun `전체 코스 등급을 그대로 옮긴다 - HARD 포함`() {
        assertEquals(Difficulty.HARD, dto(difficulty = "HARD").toDomain().difficulty)
        assertNull(dto(difficulty = null).toDomain().difficulty)
    }

    // GPX_ONLY 는 syncedAt 이 null 이 계약이다 (§6-2 와 같은 규칙).
    @Test
    fun `GPX_ONLY 는 동기화 시각이 없다`() {
        val d = dto(dataSource = "GPX_ONLY", syncedAt = null).toDomain()
        assertEquals(CourseDataSource.GPX_ONLY, d.dataSource)
        assertNull(d.syncedAt)
    }

    // 깨진 입력에 예외를 던지지 않는다 — 상세가 통째로 안 열리는 것보다 낫다 (NFR-1·3).
    @Test
    fun `못 푸는 폴리라인이어도 상세는 만들어진다`() {
        val d = dto(polyline = "!!!not-a-polyline!!!").toDomain()
        assertEquals("해파랑길 1코스", d.courseName)
    }

    @Test
    fun `고도가 없으면 빈 배열이다`() {
        assertEquals(emptyList<Int>(), dto(elevation = emptyList()).toDomain().elevationProfileM)
    }
}
