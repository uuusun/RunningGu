package com.runninggu.app.data.repository

import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.MeApi
import com.runninggu.app.data.remote.dto.MeDto
import com.runninggu.app.data.remote.dto.PasswordChangeRequest
import com.runninggu.app.data.remote.dto.PasswordChangeResponseDto
import com.runninggu.app.data.remote.dto.ReauthRequest
import com.runninggu.app.data.remote.dto.ReauthResponseDto
import com.runninggu.app.data.remote.dto.UpdateMarketingRequest
import com.runninggu.app.data.remote.dto.UpdateNicknameRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 탈퇴 계약. (API 명세 §2-2 · SPEC 결정-23 · AP-14)
 *
 * **두 걸음이다.** 재인증으로 5분 토큰을 받고, 그 토큰을 헤더에 실어 삭제한다. 한 번에
 * 지우지 않는 이유는 되돌릴 수 없는 조작이기 때문이다 — 세션만 가지고 지우면 남의 기기에
 * 열려 있던 세션으로도 계정을 없앨 수 있다.
 *
 * 앱이 틀리기 쉬운 자리는 **순서**다. 서버가 지운 뒤에 로컬을 정리해야 한다. 먼저
 * 로그아웃하면 탈퇴가 안 된 채 세션만 사라져, 사용자는 지웠다고 믿는데 계정이 남는다.
 */
class MemberWithdrawTest {

    @Test
    fun `EMAIL 재인증 본문은 비밀번호만 담는다`() {
        // 한 계정은 수단을 하나만 갖는다(결정-22 개정). 카카오 토큰까지 실어 보내면
        // 서버가 무엇으로 검증할지 모호해진다.
        val json = ApiJson.encodeToString(
            ReauthRequest.serializer(),
            ReauthRequest(provider = "EMAIL", password = "run4life1"),
        )

        assertEquals("""{"provider":"EMAIL","password":"run4life1"}""", json)
    }

    @Test
    fun `KAKAO 재인증 본문은 SDK 토큰만 담는다`() {
        val json = ApiJson.encodeToString(
            ReauthRequest.serializer(),
            ReauthRequest(provider = "KAKAO", kakaoAccessToken = "kakao-token"),
        )

        assertEquals("""{"provider":"KAKAO","kakaoAccessToken":"kakao-token"}""", json)
    }

    @Test
    fun `재인증은 5분 토큰을 돌려준다`() = runTest {
        val api = RecordingWithdrawApi(reauth = ReauthResponseDto(reauthToken = "R-5MIN", expiresInSec = 300))

        val token = RemoteMemberRepository(api).reauth(ReauthCredential.Password("run4life1"))

        assertEquals("R-5MIN", token)
        assertEquals("EMAIL", api.sentReauth?.provider)
        assertEquals("run4life1", api.sentReauth?.password)
        assertNull("가입 수단이 아닌 값이 실렸다", api.sentReauth?.kakaoAccessToken)
    }

    @Test
    fun `탈퇴는 재인증 토큰을 헤더로 보낸다`() = runTest {
        // 본문이 아니라 헤더다(§2-2 `X-Reauth-Token`). 자리를 틀리면 서버가 토큰이
        // 없는 것으로 보고 `401 INVALID_REAUTH_TOKEN` 을 준다.
        val api = RecordingWithdrawApi()

        RemoteMemberRepository(api).withdraw("R-5MIN")

        assertEquals("R-5MIN", api.sentReauthToken)
    }

    @Test
    fun `만료된 토큰은 코드를 지운 채 올라오지 않는다`() = runTest {
        // 화면이 "재인증부터 다시" 로 안내하려면 code 가 살아 있어야 한다.
        val api = RecordingWithdrawApi(
            error = ApiException.Http(401, ApiErrorCode.INVALID_REAUTH_TOKEN, null),
        )

        val thrown = runCatching { RemoteMemberRepository(api).withdraw("EXPIRED") }.exceptionOrNull()

        assertEquals(ApiErrorCode.INVALID_REAUTH_TOKEN, (thrown as? ApiException.Http)?.code)
    }

    @Test
    fun `가입한 것과 다른 수단이면 그 코드가 그대로 온다`() = runTest {
        // `409 REAUTH_PROVIDER_MISMATCH` — 재시도할 일이 아니라 **다른 수단으로** 할 일이다.
        val api = RecordingWithdrawApi(
            error = ApiException.Http(409, ApiErrorCode.REAUTH_PROVIDER_MISMATCH, null),
        )

        val thrown = runCatching {
            RemoteMemberRepository(api).reauth(ReauthCredential.Kakao("t"))
        }.exceptionOrNull()

        assertEquals(ApiErrorCode.REAUTH_PROVIDER_MISMATCH, (thrown as? ApiException.Http)?.code)
    }

    @Test
    fun `재인증이 실패하면 탈퇴로 넘어갈 토큰이 없다`() = runTest {
        val api = RecordingWithdrawApi(error = ApiException.Http(401, ApiErrorCode.REAUTH_FAILED, null))

        val result = runCatching {
            RemoteMemberRepository(api).reauth(ReauthCredential.Password("wrong"))
        }

        assertTrue(result.isFailure)
        assertNull("실패인데 토큰이 돌아왔다", result.getOrNull())
    }
}

/** 무엇을 보냈는지 적어 두는 가짜. */
private class RecordingWithdrawApi(
    private val reauth: ReauthResponseDto? = null,
    private val error: ApiException? = null,
) : MeApi {

    var sentReauth: ReauthRequest? = null
        private set
    var sentReauthToken: String? = null
        private set

    override suspend fun reauth(body: ReauthRequest): ReauthResponseDto {
        sentReauth = body
        error?.let { throw it }
        return requireNotNull(reauth)
    }

    override suspend fun withdraw(reauthToken: String) {
        sentReauthToken = reauthToken
        error?.let { throw it }
    }

    override suspend fun me(): MeDto = throw UnsupportedOperationException("이 테스트는 안 부른다")

    override suspend fun updateNickname(body: UpdateNicknameRequest): MeDto =
        throw UnsupportedOperationException("이 테스트는 안 부른다")

    override suspend fun updateMarketing(body: UpdateMarketingRequest): MeDto =
        throw UnsupportedOperationException("이 테스트는 안 부른다")

    override suspend fun updatePassword(body: PasswordChangeRequest): PasswordChangeResponseDto =
        throw UnsupportedOperationException("이 테스트는 안 부른다")
}
