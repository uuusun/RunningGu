package com.runninggu.app.data.repository

import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.AuthApi
import com.runninggu.app.data.remote.RefreshRequestDto
import com.runninggu.app.data.remote.RefreshResponseDto
import com.runninggu.app.data.remote.TokenApi
import com.runninggu.app.data.remote.dto.AuthTokenResponseDto
import com.runninggu.app.data.remote.dto.AuthUserDto
import com.runninggu.app.data.remote.dto.ExistsResponseDto
import com.runninggu.app.data.remote.dto.KakaoLoginRequestDto
import com.runninggu.app.data.remote.dto.KakaoLoginResponseDto
import com.runninggu.app.data.remote.dto.KakaoProfileDto
import com.runninggu.app.data.remote.dto.KakaoSignupRequestDto
import com.runninggu.app.data.remote.dto.LoginRequestDto
import com.runninggu.app.data.remote.dto.LogoutRequestDto
import com.runninggu.app.data.remote.dto.ResetPasswordRequestDto
import com.runninggu.app.data.remote.dto.ResetRequestDto
import com.runninggu.app.data.remote.dto.SendCodeRequestDto
import com.runninggu.app.data.remote.dto.SignupRequestDto
import com.runninggu.app.data.remote.dto.VerifyCodeRequestDto
import com.runninggu.app.data.remote.dto.VerifyCodeResponseDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 카카오 로그인·가입 계약. (API 명세 §1-7 · §1-8 · 이슈 #206)
 *
 * **한 `200` 이 두 가지를 뜻한다.** 기존 KAKAO 가입자면 토큰이, 미가입이면 프로필이 온다.
 * 상태 코드로는 구분되지 않아서 [KakaoLoginOutcome] 으로 가른다.
 *
 * 화면이 `isNewUser` 를 직접 보게 두면 "true 인데 토큰을 읽는" 조합이 만들어지고 컴파일이
 * 잡아 주지 않는다. 이 파일은 **그 갈림이 실제로 맞게 서는지**를 고정한다.
 */
class KakaoAuthContractTest {

    private class FakeApi(
        private val loginResponse: KakaoLoginResponseDto? = null,
        private val signupResponse: AuthTokenResponseDto? = null,
    ) : AuthApi {
        var sentLogin: KakaoLoginRequestDto? = null
        var sentSignup: KakaoSignupRequestDto? = null

        override suspend fun kakaoLogin(body: KakaoLoginRequestDto): KakaoLoginResponseDto {
            sentLogin = body
            return requireNotNull(loginResponse)
        }

        override suspend fun kakaoSignup(body: KakaoSignupRequestDto): AuthTokenResponseDto {
            sentSignup = body
            return requireNotNull(signupResponse)
        }

        override suspend fun login(body: LoginRequestDto): AuthTokenResponseDto = TODO()
        override suspend fun emailExists(email: String): ExistsResponseDto = TODO()
        override suspend fun nicknameExists(nickname: String): ExistsResponseDto = TODO()
        override suspend fun sendSignupCode(body: SendCodeRequestDto) = TODO()
        override suspend fun verifySignupCode(body: VerifyCodeRequestDto): VerifyCodeResponseDto = TODO()
        override suspend fun signup(body: SignupRequestDto): AuthTokenResponseDto = TODO()
        override suspend fun requestPasswordReset(body: ResetRequestDto) = TODO()
        override suspend fun resetPassword(body: ResetPasswordRequestDto) = TODO()
    }

    private object UnusedTokenApi : TokenApi {
        override suspend fun refresh(body: RefreshRequestDto): RefreshResponseDto = TODO()
        override suspend fun logout(body: LogoutRequestDto) = TODO()
    }

    private fun repository(api: FakeApi) = RemoteAuthRepository(api = api, tokenApi = UnusedTokenApi)

    // ── 로그인 (§1-7) ──────────────────────────────────────────

    @Test
    fun `기존 가입자는 세션으로 온다`() = runBlocking {
        val api = FakeApi(
            loginResponse = KakaoLoginResponseDto(
                isNewUser = false,
                accessToken = "A1",
                refreshToken = "R1",
                user = AuthUserDto(id = 7, nickname = "달리는민지", email = null, loginProvider = "KAKAO"),
            ),
        )

        val outcome = repository(api).kakaoLogin("KAKAO-TOKEN").getOrThrow()

        val session = assertIs<KakaoLoginOutcome.Session>(outcome)
        assertEquals("A1", session.session.tokens.accessToken)
        assertEquals("달리는민지", session.session.profile.nickname)
        // KAKAO 가입자는 이메일이 없을 수 있다. 화면이 행을 숨긴다 (§2 · #59)
        assertNull(session.session.profile.email)
        assertEquals(LoginProvider.KAKAO, session.session.profile.loginProvider)
        assertEquals("KAKAO-TOKEN", api.sentLogin?.kakaoAccessToken)
    }

    @Test
    fun `미가입자는 프로필과 함께 가입 화면으로 간다`() = runBlocking {
        val api = FakeApi(
            loginResponse = KakaoLoginResponseDto(
                isNewUser = true,
                kakaoProfile = KakaoProfileDto(nickname = "민지", email = "m@example.com"),
            ),
        )

        val outcome = repository(api).kakaoLogin("KAKAO-TOKEN").getOrThrow()

        val newUser = assertIs<KakaoLoginOutcome.NewUser>(outcome)
        assertEquals("민지", newUser.nickname)
        assertEquals("m@example.com", newUser.email)
        // 가입 요청이 같은 토큰을 다시 요구한다. 화면이 따로 보관하지 않게 함께 들려 보낸다
        assertEquals("KAKAO-TOKEN", newUser.kakaoAccessToken)
    }

    @Test
    fun `카카오가 프로필을 안 줘도 가입 화면으로 간다`() = runBlocking {
        // 동의 항목에 따라 닉네임도 이메일도 안 올 수 있다 (§1-7 · §4.1).
        // 화면은 초기값으로만 쓰고 없으면 사용자가 직접 넣는다
        val api = FakeApi(loginResponse = KakaoLoginResponseDto(isNewUser = true))

        val outcome = repository(api).kakaoLogin("T").getOrThrow()

        val newUser = assertIs<KakaoLoginOutcome.NewUser>(outcome)
        assertNull(newUser.nickname)
        assertNull(newUser.email)
    }

    @Test
    fun `기존 가입자라면서 토큰을 안 주면 실패로 올린다`() = runBlocking {
        // 세션 없이 홈으로 보내면 다음 요청마다 401 이 나고 사용자는 이유를 모른다 —
        // "로그인은 됐는데 아무것도 안 되는" 상태가 제일 나쁘다
        val api = FakeApi(loginResponse = KakaoLoginResponseDto(isNewUser = false))

        val result = repository(api).kakaoLogin("T")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ApiException.Malformed)
    }

    // ── 가입 (§1-8) ────────────────────────────────────────────

    @Test
    fun `가입이 곧 로그인이다`() = runBlocking {
        val api = FakeApi(
            signupResponse = AuthTokenResponseDto(
                accessToken = "A2",
                refreshToken = "R2",
                user = AuthUserDto(id = 7, nickname = "민지", email = null, loginProvider = "KAKAO"),
            ),
        )

        val session = repository(api).kakaoSignup("T", "민지", marketingAgreed = true, ageOver14 = true).getOrThrow()

        assertEquals("A2", session.tokens.accessToken)
        assertEquals(LoginProvider.KAKAO, session.profile.loginProvider)
    }

    @Test
    fun `필수 동의는 참으로 마케팅은 화면 값으로 나간다`() = runBlocking {
        val api = FakeApi(
            signupResponse = AuthTokenResponseDto(
                accessToken = "A2",
                refreshToken = "R2",
                user = AuthUserDto(id = 7, nickname = "민지", email = null, loginProvider = "KAKAO"),
            ),
        )

        repository(api).kakaoSignup("T", "  민지  ", marketingAgreed = false, ageOver14 = true)

        val sent = requireNotNull(api.sentSignup)
        assertTrue(sent.agreements.tos)
        assertTrue(sent.agreements.privacy)
        assertEquals(false, sent.agreements.marketing)
        // 앞뒤 공백만 없앤다. 소문자화는 서버가 한다 (#97)
        assertEquals("민지", sent.nickname)
    }

    // ── 기본 구현 ──────────────────────────────────────────────

    @Test
    fun `카카오를 모르는 구현은 조용히 성공하지 않는다`() {
        // 기본 구현이 성공한 척하면 화면이 "로그인됐다" 로 그린다
        val stub = object : AuthRepository {
            override suspend fun emailExists(email: String) = TODO()
            override suspend fun nicknameExists(nickname: String) = TODO()
            override suspend fun login(email: String, password: String) = TODO()
            override suspend fun sendSignupCode(email: String) = TODO()
            override suspend fun verifySignupCode(email: String, code: String) = TODO()
            override suspend fun signup(
                email: String,
                password: String,
                nickname: String,
                marketingAgreed: Boolean,
        ageOver14: Boolean,
            ) = TODO()
            override suspend fun requestPasswordReset(email: String) = TODO()
            override suspend fun logout(refreshToken: String) = TODO()
        }

        runBlocking {
            try {
                stub.kakaoLogin("T")
                error("여기 오면 안 된다")
            } catch (expected: UnsupportedOperationException) {
                assertTrue(expected.message.orEmpty().contains("카카오"))
            }
        }
    }

    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue("기대한 타입이 아니다: ${value?.let { it::class.simpleName }}", value is T)
        return value as T
    }
}
