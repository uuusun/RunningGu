package com.runninggu.app.ui.home

import com.runninggu.app.ui.OFFLINE
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.httpErrorOf
import com.runninggu.app.ui.sectionMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * 홈의 두 영역이 **서로 다른 오류 문구를 낼 수 있는가.** (이슈 #260 · API 명세 §0-3)
 *
 * 화면은 영역마다 기본 문구를 들고 있다.
 *
 * ```
 * section(state = uiState.closingSoon, errorMessage = "마감 임박 대회를 불러오지 못했어요")
 * section(state = uiState.festivals,  errorMessage = "축제 정보를 불러오지 못했어요")
 * ```
 *
 * 그런데 ViewModel 이 `userMessageOrDefault()` 로 항상 문자열을 채워 넣어서,
 * `state.message ?: errorMessage` 의 **오른쪽이 영원히 실행되지 않았다.** 두 영역이 같은
 * "정보를 불러오지 못했어요." 를 내고 어느 쪽이 죽었는지 알 수 없었다.
 *
 * 그래서 이 파일이 보는 것은 문구 값이 아니라 **null 이 나오는가**다 — null 이어야 화면의
 * 영역별 기본 문구가 산다.
 */
class HomeSectionErrorTest {

    @Test
    fun `서버 문구가 없으면 null 이라 영역 기본 문구가 산다`() {
        // 프록시가 HTML 오류 페이지를 주면 problem 이 null 이다 (`httpErrorOf`)
        assertNull(httpErrorOf(502, "<html>502</html>").sectionMessage())
        assertNull(httpErrorOf(500, null).sectionMessage())
    }

    @Test
    fun `서버가 문구를 주면 그것을 쓴다`() {
        val failure = httpErrorOf(
            502,
            """{"title":"외부 API 호출에 실패했습니다.","status":502,"code":"EXTERNAL_API_ERROR"}""",
        )

        assertEquals("외부 API 호출에 실패했습니다.", failure.sectionMessage())
    }

    @Test
    fun `네트워크가 끊긴 것은 따로 말한다`() {
        // 연결을 고쳐야 하는 것과 잠시 뒤 다시 눌러야 하는 것은 사용자가 할 일이 다르다.
        // 다른 화면들은 이미 갈라 놓았고 홈만 빠져 있었다
        assertEquals(
            OFFLINE,
            ApiException.Network(IOException("offline")).sectionMessage(),
        )
    }

    @Test
    fun `계약 밖 응답도 영역 기본 문구에 맡긴다`() {
        // Malformed 에 별도 문구를 만들면 홈 영역마다 또 갈라야 한다.
        // 화면이 이미 "마감 임박 대회를 불러오지 못했어요" 를 들고 있으므로 그쪽이 낫다
        assertNull(ApiException.Malformed(RuntimeException("bad")).sectionMessage())
    }
}
