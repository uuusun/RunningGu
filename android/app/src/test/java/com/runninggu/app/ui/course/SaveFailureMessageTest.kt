package com.runninggu.app.ui.course

import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.ProblemDetail
import com.runninggu.app.ui.SAVE_FAILED_OUTSIDE_CONTRACT
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

    private fun http(code: ApiErrorCode, title: String?, status: Int = 400) = ApiException.Http(
        status = status,
        code = code,
        problem = title?.let { ProblemDetail(title = it, code = code.name) },
    )

    @Test
    fun `서버가 거절하면 서버가 준 문구를 낸다`() {
        // 왜 거절했는지는 서버만 안다. 백엔드 `ErrorCode` 가 code 마다 한국어 title 을 준다
        val message = http(ApiErrorCode.VALIDATION_FAILED, "요청 값이 올바르지 않습니다.").saveMessage()

        assertEquals("요청 값이 올바르지 않습니다.", message)
    }

    @Test
    fun `서버 문구가 없으면 code 라도 남긴다`() {
        // 프록시가 HTML 오류 페이지를 주면 `problem` 이 null 이다 (`httpErrorOf` KDoc).
        // 사용자에게 코드를 보이는 건 좋지 않지만 아무 단서 없이 뭉개는 것보다 낫다
        val message = http(ApiErrorCode.INTERNAL_SERVER_ERROR, null, status = 500).saveMessage()

        assertEquals("저장하지 못했어요. (INTERNAL_SERVER_ERROR)", message)
    }

    @Test
    fun `네트워크가 끊긴 것은 따로 말한다`() {
        val message = ApiException.Network(IOException("offline")).saveMessage()

        assertEquals("네트워크에 연결할 수 없어요.", message)
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
            http(ApiErrorCode.VALIDATION_FAILED, "요청 값이 올바르지 않습니다.").saveMessage(),
            http(ApiErrorCode.INTERNAL_SERVER_ERROR, null, status = 500).saveMessage(),
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
            http(ApiErrorCode.INTERNAL_SERVER_ERROR, null, status = 500).saveMessage(),
        )
    }
}
