package com.runninggu.app.ui.course

import com.runninggu.app.ui.OFFLINE
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.httpErrorOf
import com.runninggu.app.ui.SAVE_FAILED_OUTSIDE_CONTRACT
import com.runninggu.app.ui.diagnostic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 저장이 왜 실패했는지가 화면에서 갈리는가. (이슈 #252 · API 명세 §0-3)
 *
 * **문구가 서로 다른 것 자체가 계약이다.** 예전에는 `Network` 가 아닌 모든 실패가
 * "저장하지 못했어요. 잠시 뒤 다시 시도해 주세요." 하나로 떨어졌고, 그 문구는 두
 * ViewModel 의 `catch (e: Throwable)` 이 내는 것과 **글자 하나까지 같았다.**
 *
 * 그래서 #245 를 사흘 동안 엉뚱한 데서 찾았다 — 평범한 `400` 이었는데 화면이 계약 밖
 * 실패와 똑같이 보여서 직렬화·매퍼를 뒤졌다. 원인은 요청 본문에서 `blockType` 이 빠진
 * 것이었다(#251).
 *
 * 그러니 여기서 값을 하나씩 고정하는 것만으로는 부족하다. **넷이 서로 겹치지 않는지**를
 * 따로 본다 — 값만 고정하면 나중에 둘을 같은 문구로 만들어도 테스트가 통과한다.
 */
class SaveFailureMessageTest {

    /**
     * **실제 변환 경로로 만든다.** `ApiException.Http` 를 손으로 조립하면 파서를 건너뛰어,
     * 현실에 없는 조합(본문이 없는데 `code` 는 구체적)으로 테스트하게 된다(#254 리뷰).
     */
    private fun failure(status: Int, body: String?) = httpErrorOf(status, body)

    /** 서버가 실제로 내려주는 problem+json. `title` 과 `code` 가 **함께** 온다. */
    private fun problemJson(
        code: String,
        title: String,
        traceId: String = "9f2c1ab34d",
        errors: String = "",
    ) = """
        {"type":"about:blank","title":"$title","status":400,
         "detail":"save rejected","instance":"/api/itineraries",
         "code":"$code","traceId":"$traceId"$errors}
    """.trimIndent()

    @Test
    fun `서버가 거절하면 서버가 준 문구를 낸다`() {
        // 왜 거절했는지는 서버만 안다. 백엔드 `ErrorCode` 가 code 마다 한국어 title 을 준다
        val failure = failure(400, problemJson("VALIDATION_FAILED", "요청 값이 올바르지 않습니다."))

        assertEquals("요청 값이 올바르지 않습니다.", failure.saveMessage())
    }

    @Test
    fun `title 과 code 가 함께 와도 code 가 사라지지 않는다`() {
        // **#254 리뷰의 본체다.** 정상 problem+json 은 둘을 함께 준다. 화면은 `title` 만
        // 쓰므로, `code` 와 `traceId` 가 로그로 안 가면 앱에서 통째로 사라진다 —
        // 서버 로그와 이어 볼 끈이 그것뿐이다
        val failure = failure(
            400,
            problemJson(
                code = "VALIDATION_FAILED",
                title = "요청 값이 올바르지 않습니다.",
                traceId = "9f2c1ab34d",
                errors = ""","errors":[{"field":"days[0].blocks[0].blockType","reason":"must not be blank"}]""",
            ),
        )

        assertEquals("요청 값이 올바르지 않습니다.", failure.saveMessage())

        val diagnostic = failure.diagnostic()
        assertTrue("code 가 없다: $diagnostic", diagnostic.contains("VALIDATION_FAILED"))
        assertTrue("traceId 가 없다: $diagnostic", diagnostic.contains("9f2c1ab34d"))
        assertTrue("status 가 없다: $diagnostic", diagnostic.contains("400"))
        // #245 를 사흘 만에 찾게 한 그 필드다. 이름만 있으면 어디가 문제인지 바로 안다
        assertTrue("필드 이름이 없다: $diagnostic", diagnostic.contains("blockType"))
    }

    @Test
    fun `진단에 사용자가 넣은 값이 섞일 자리를 남기지 않는다`() {
        // `detail` 과 `errors[].reason` 은 담지 않는다 — 서버가 거절 사유에 입력값을
        // 되비출 여지가 있고, 로그에 남기면 안 되는 것 목록과 부딪힌다 (AGENTS 8장)
        val diagnostic = failure(
            400,
            problemJson(
                code = "VALIDATION_FAILED",
                title = "요청 값이 올바르지 않습니다.",
                errors = ""","errors":[{"field":"nickname","reason":"must not be blank"}]""",
            ),
        ).diagnostic()

        assertTrue("detail 이 섞였다: $diagnostic", !diagnostic.contains("save rejected"))
        assertTrue("reason 이 섞였다: $diagnostic", !diagnostic.contains("must not be blank"))
    }

    @Test
    fun `problem 이 아닌 본문이면 상태 코드로 말한다`() {
        // 프록시가 HTML 오류 페이지를 주면 `problem` 이 null 이고, 그때 `code` 는
        // **늘 `UNKNOWN`** 이라 화면에 적어도 아무 말이 아니다 (#254 리뷰).
        // 실제 변환 경로로 확인한다 — 손으로 조립하면 이 사실이 안 드러난다
        val failure = failure(502, "<html><body>502 Bad Gateway</body></html>")

        assertEquals(ApiErrorCode.UNKNOWN, failure.code)
        assertEquals("저장하지 못했어요. (서버 응답 502)", failure.saveMessage())
        // 화면에서 사라진 것은 로그에 남는다
        assertTrue(failure.diagnostic().contains("502"))
    }

    @Test
    fun `네트워크가 끊긴 것은 따로 말한다`() {
        val message = ApiException.Network(IOException("offline")).saveMessage()

        assertEquals(OFFLINE, message)
    }

    @Test
    fun `응답을 못 읽었으면 다시 시도하라고 하지 않는다`() {
        // `Malformed` 는 **성공 응답**을 못 읽은 것이라(`apiCall` 의 SerializationException
        // 갈래) 서버에는 이미 저장돼 있을 수 있다. 다시 누르라고 하면 두 번 저장한다
        val message = ApiException.Malformed(RuntimeException("bad json")).saveMessage()

        assertTrue("다시 시도하라고 말하면 안 된다: $message", !message.contains("다시 시도"))
        assertTrue("어디서 확인할지 말해야 한다: $message", message.contains("마이"))
    }

    @Test
    fun `계약 밖 실패는 서버 거절과 다른 문구다`() {
        // **이 테스트가 #252 의 본체다.** 넷이 서로 겹치면 화면만 보고 원인을 못 가린다
        val messages = listOf(
            failure(400, problemJson("VALIDATION_FAILED", "요청 값이 올바르지 않습니다.")).saveMessage(),
            failure(502, "<html>502</html>").saveMessage(),
            ApiException.Network(IOException("offline")).saveMessage(),
            ApiException.Malformed(RuntimeException("bad json")).saveMessage(),
            SAVE_FAILED_OUTSIDE_CONTRACT,
        )

        assertEquals("문구가 서로 겹친다: $messages", messages.size, messages.toSet().size)
    }

    @Test
    fun `계약 밖 문구는 두 화면이 같은 상수를 쓴다`() {
        // S7·S8 에 문자열을 각각 적어 두면 한쪽만 고쳐질 수 있다. 그러면 위 테스트가
        // 자기 사본과 비교하게 되어 겹침을 못 잡는다
        assertNotEquals(
            "계약 밖 문구가 서버 거절 문구와 같아졌다",
            SAVE_FAILED_OUTSIDE_CONTRACT,
            failure(502, "<html>502</html>").saveMessage(),
        )
    }
}
