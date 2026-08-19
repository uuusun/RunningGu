package com.runninggu.app.data.remote

import com.runninggu.app.data.remote.dto.CursorPageDto
import com.runninggu.app.data.remote.dto.PageDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * 서버 공통 계약을 고정한다. (API 명세 §0 · NFR-17)
 *
 * 백엔드 엔드포인트가 아직 없으므로 명세에 실린 예시 JSON 을 그대로 넣어 검증한다.
 * 계약이 바뀌면 이 테스트가 먼저 깨져야 한다.
 */
class ApiContractTest {

    // ── §0-3 에러 응답 ──────────────────────────────────────────

    @Test
    fun `problem+json 을 코드까지 읽는다`() {
        // 명세 §0-3 의 예시 그대로
        val body = """
            {
              "type": "/errors/system-block-immutable",
              "title": "변경할 수 없는 일정입니다.",
              "status": 409,
              "detail": "대회 일정은 사용자가 변경할 수 없습니다.",
              "instance": "/api/itineraries/3/days/9/blocks/21",
              "code": "SYSTEM_BLOCK_IMMUTABLE",
              "traceId": "7f3d8c..."
            }
        """.trimIndent()

        val e = httpErrorOf(409, body)

        assertEquals(409, e.status)
        assertEquals(ApiErrorCode.SYSTEM_BLOCK_IMMUTABLE, e.code)
        assertEquals("변경할 수 없는 일정입니다.", e.userMessage)
        assertEquals("7f3d8c...", e.problem?.traceId)
        assertFalse(e.needsLogin)
    }

    @Test
    fun `검증 실패는 필드별 사유를 읽는다`() {
        val body = """
            {
              "status": 400, "code": "VALIDATION_FAILED",
              "errors": [{"field": "email", "reason": "형식이 올바르지 않습니다"}]
            }
        """.trimIndent()

        val e = httpErrorOf(400, body)

        assertEquals(ApiErrorCode.VALIDATION_FAILED, e.code)
        assertEquals(1, e.problem?.errors?.size)
        assertEquals("email", e.problem?.errors?.first()?.field)
    }

    @Test
    fun `리소스별 404 는 하나로 묶는다`() {
        // 부록 D — `CONTEST_NOT_FOUND` 등 `*_NOT_FOUND`
        assertEquals(ApiErrorCode.NOT_FOUND, ApiErrorCode.from("CONTEST_NOT_FOUND"))
        assertEquals(ApiErrorCode.NOT_FOUND, ApiErrorCode.from("ITINERARY_NOT_FOUND"))
    }

    @Test
    fun `모르는 코드는 UNKNOWN 으로 떨어진다`() {
        // 서버가 코드를 새로 추가해도 앱이 깨지면 안 된다
        assertEquals(ApiErrorCode.UNKNOWN, ApiErrorCode.from("SOMETHING_NEW"))
        assertEquals(ApiErrorCode.UNKNOWN, ApiErrorCode.from(null))
        assertEquals(ApiErrorCode.UNKNOWN, ApiErrorCode.from(""))
    }

    @Test
    fun `problem+json 이 아니어도 상태 코드는 살린다`() {
        // 게이트웨이가 HTML 오류 페이지를 돌려주는 경우
        val e = httpErrorOf(502, "<html><body>Bad Gateway</body></html>")

        assertEquals(502, e.status)
        assertEquals(ApiErrorCode.UNKNOWN, e.code)
        assertNull(e.problem)
        assertNull(e.userMessage)
    }

    @Test
    fun `본문이 비어도 실패를 만든다`() {
        assertEquals(504, httpErrorOf(504, null).status)
        assertEquals(500, httpErrorOf(500, "").status)
    }

    @Test
    fun `401 은 로그인 모달로 보낸다`() {
        // §0-2 — 게스트의 쓰기 시도 포함
        assertTrue(httpErrorOf(401, """{"code":"UNAUTHORIZED"}""").needsLogin)
        assertFalse(httpErrorOf(403, """{"code":"FORBIDDEN"}""").needsLogin)
    }

    @Test
    fun `외부 API 실패를 구분한다`() {
        // NFR-3~5 — 재시도가 의미 있는 자리
        assertTrue(httpErrorOf(502, """{"code":"EXTERNAL_API_ERROR"}""").isExternal)
        assertTrue(httpErrorOf(504, """{"code":"EXTERNAL_API_TIMEOUT"}""").isExternal)
        assertFalse(httpErrorOf(503, """{"code":"COURSE_SOURCES_UNAVAILABLE"}""").isExternal)
    }

    // ── §0-1 날짜·시각 ─────────────────────────────────────────

    @Serializable
    private data class TimeSample(
        @kotlinx.serialization.Contextual val date: LocalDate,
        @kotlinx.serialization.Contextual val at: Instant,
    )

    @Test
    fun `비즈니스 날짜와 timestamp 를 규약대로 읽는다`() {
        val s = ApiJson.decodeFromString(
            TimeSample.serializer(),
            """{"date":"2026-06-06","at":"2026-06-01T11:38:13Z"}""",
        )

        assertEquals(LocalDate.of(2026, 6, 6), s.date)
        assertEquals(Instant.parse("2026-06-01T11:38:13Z"), s.at)
        // 왕복해도 같은 문자열이어야 한다 — 서버에 되돌려 보낼 때 어긋나면 안 된다
        assertEquals(
            """{"date":"2026-06-06","at":"2026-06-01T11:38:13Z"}""",
            ApiJson.encodeToString(TimeSample.serializer(), s),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `날짜 형식이 다르면 조용히 넘기지 않는다`() {
        ApiJson.decodeFromString(TimeSample.serializer(), """{"date":"2026/06/06","at":"2026-06-01T11:38:13Z"}""")
    }

    // ── §0-4 페이징 ────────────────────────────────────────────

    @Test
    fun `대회 목록은 불투명 커서를 그대로 들고 다닌다`() {
        val page = ApiJson.decodeFromString(
            CursorPageDto.serializer(String.serializer()),
            """{"items":["a","b"],"nextCursor":"eyJkIjoiMjAyNi0wNi0wNiJ9"}""",
        )

        assertEquals(listOf("a", "b"), page.items)
        // 앱은 해석하지 않는다 — 그대로 다음 요청에 돌려준다
        assertEquals("eyJkIjoiMjAyNi0wNi0wNiJ9", page.nextCursor)
    }

    @Test
    fun `마지막 페이지는 커서가 없다`() {
        val page = ApiJson.decodeFromString(
            CursorPageDto.serializer(String.serializer()),
            """{"items":[]}""",
        )

        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun `개인 목록은 Pageable 응답을 읽는다`() {
        val page = ApiJson.decodeFromString(
            PageDto.serializer(String.serializer()),
            """{"content":["x"],"page":{"number":0,"size":20,"totalElements":1,"hasNext":false}}""",
        )

        assertEquals(listOf("x"), page.content)
        assertEquals(20, page.page.size)
        assertFalse(page.page.hasNext)
    }

    // ── 서버가 필드를 추가해도 깨지지 않는다 ──────────────────────

    @Test
    fun `모르는 필드는 무시한다`() {
        val e = httpErrorOf(409, """{"code":"EMAIL_DUPLICATED","futureField":{"a":1}}""")
        assertEquals(ApiErrorCode.EMAIL_DUPLICATED, e.code)
    }
}
