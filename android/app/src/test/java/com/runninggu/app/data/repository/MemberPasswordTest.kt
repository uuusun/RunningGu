package com.runninggu.app.data.repository

import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.MeApi
import com.runninggu.app.data.remote.dto.MeDto
import com.runninggu.app.data.remote.dto.PasswordChangeRequest
import com.runninggu.app.data.remote.dto.PasswordChangeResponseDto
import com.runninggu.app.data.remote.dto.UpdateMarketingRequest
import com.runninggu.app.data.remote.dto.UpdateNicknameRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 비밀번호 변경 계약. (API 명세 §2-1 · SPEC 결정-28 · AP-14)
 *
 * `PATCH /me` 셋과 **모양이 다른 유일한 자리**다 — 프로필이 아니라 새 token pair 가 온다.
 * 서버가 한 트랜잭션에서 기존 refresh 를 전부 revoke 하고 현재 기기용으로 다시 발급하기
 * 때문이다.
 *
 * **그래서 두 토큰이 같이 와야 한다.** 액세스만 갈아끼우면 다음 재발급이 실패해서,
 * 방금 비밀번호를 바꾼 사용자가 로그아웃된다.
 */
class MemberPasswordTest {

    @Test
    fun `요청 본문이 명세 필드명 그대로 나간다`() {
        // 서버는 currentPassword·newPassword 로 받는다(§2-1). 이름이 어긋나면
        // `400 VALIDATION_FAILED` 인데, 화면에는 "형식이 틀렸다" 로 보여 원인이 안 보인다.
        val json = ApiJson.encodeToString(
            PasswordChangeRequest.serializer(),
            PasswordChangeRequest(currentPassword = "run4life1", newPassword = "newRun4life2"),
        )

        assertEquals("""{"currentPassword":"run4life1","newPassword":"newRun4life2"}""", json)
    }

    @Test
    fun `응답의 두 토큰을 함께 돌려준다`() = runTest {
        val api = RecordingMeApi(
            response = PasswordChangeResponseDto(accessToken = "A2", refreshToken = "R2"),
        )
        val repository = RemoteMemberRepository(api)

        val tokens = repository.updatePassword("run4life1", "newRun4life2")

        assertEquals("A2", tokens.accessToken)
        // **여기가 핵심이다.** 리프레시를 빠뜨리면 옛 R1 이 남는데 서버는 그걸 revoke 했다.
        assertEquals("R2", tokens.refreshToken)
        assertEquals("run4life1", api.sent?.currentPassword)
        assertEquals("newRun4life2", api.sent?.newPassword)
    }

    @Test
    fun `현재 비밀번호 불일치는 코드를 지운 채 올라오지 않는다`() = runTest {
        // 화면이 문구를 가르려면 code 가 살아 있어야 한다 — 형식 위반(INVALID_PASSWORD)과
        // 할 일이 다르다. 전자는 다시 입력, 후자는 다른 비밀번호를 고르는 일이다.
        val api = RecordingMeApi(
            error = ApiException.Http(400, ApiErrorCode.CURRENT_PASSWORD_MISMATCH, null),
        )
        val repository = RemoteMemberRepository(api)

        val thrown = runCatching { repository.updatePassword("wrong1234", "newRun4life2") }
            .exceptionOrNull()

        val http = thrown as? ApiException.Http
        assertEquals(ApiErrorCode.CURRENT_PASSWORD_MISMATCH, http?.code)
        assertEquals(400, http?.status)
    }

    @Test
    fun `실패하면 토큰을 만들지 않는다`() = runTest {
        // 실패인데 빈 토큰을 돌려주면 호출부가 그걸 저장해 세션이 죽는다.
        val api = RecordingMeApi(
            error = ApiException.Http(400, ApiErrorCode.INVALID_PASSWORD, null),
        )
        val repository = RemoteMemberRepository(api)

        val result = runCatching { repository.updatePassword("run4life1", "short") }

        assertNull("실패인데 토큰이 돌아왔다", result.getOrNull())
    }
}

/** 무엇을 보냈는지 적어 두는 가짜. 나머지 호출은 이 테스트가 쓰지 않는다. */
private class RecordingMeApi(
    private val response: PasswordChangeResponseDto? = null,
    private val error: ApiException? = null,
) : MeApi {

    var sent: PasswordChangeRequest? = null
        private set

    override suspend fun updatePassword(body: PasswordChangeRequest): PasswordChangeResponseDto {
        sent = body
        error?.let { throw it }
        return requireNotNull(response)
    }

    override suspend fun me(): MeDto = throw UnsupportedOperationException("이 테스트는 안 부른다")

    override suspend fun updateNickname(body: UpdateNicknameRequest): MeDto =
        throw UnsupportedOperationException("이 테스트는 안 부른다")

    override suspend fun updateMarketing(body: UpdateMarketingRequest): MeDto =
        throw UnsupportedOperationException("이 테스트는 안 부른다")
}
