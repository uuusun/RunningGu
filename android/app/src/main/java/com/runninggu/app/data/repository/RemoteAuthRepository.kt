package com.runninggu.app.data.repository

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.AuthApi
import com.runninggu.app.data.remote.TokenApi
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.dto.AgreementsRequestDto
import com.runninggu.app.data.remote.dto.AuthTokenResponseDto
import com.runninggu.app.data.remote.dto.KakaoLoginRequestDto
import com.runninggu.app.data.remote.dto.KakaoLoginResponseDto
import com.runninggu.app.data.remote.dto.KakaoSignupRequestDto
import com.runninggu.app.data.remote.dto.LoginRequestDto
import com.runninggu.app.data.remote.dto.LogoutRequestDto
import com.runninggu.app.data.remote.dto.ResetRequestDto
import com.runninggu.app.data.remote.dto.SendCodeRequestDto
import com.runninggu.app.data.remote.dto.SignupRequestDto
import com.runninggu.app.data.remote.dto.VerifyCodeRequestDto

/**
 * 서버 구현. (API 명세 §1)
 *
 * **다듬는 자리를 여기 하나로 모은다.** 중복 확인·발송·검증·가입·로그인이 조금이라도 다른
 * 문자열을 보내면 결과가 어긋나는데(확인은 통과했는데 가입에서 `409`), 화면이 각자
 * `trim()` 하면 언젠가 한 곳이 빠진다(이슈 #97).
 *
 * **소문자 정규화는 하지 않는다.** 그건 서버 몫이다 — 규칙이 두 벌이면 갈라진다.
 */
class RemoteAuthRepository(
    private val api: AuthApi,
    /**
     * 로그아웃 전용. **인증자 없는 클라이언트**라 [api] 와 갈라 받는다 (이슈 #113).
     *
     * 저장소 하나가 API 둘을 보는 게 어색해 보이지만, 갈리는 기준은 "무슨 기능이냐" 가
     * 아니라 **"인증자를 타도 되느냐"** 다. 화면은 그 차이를 알 필요가 없어 여기서 숨긴다.
     */
    private val tokenApi: TokenApi,
) : AuthRepository {

    override suspend fun emailExists(email: String): Result<Boolean> = call {
        api.emailExists(email.normalized()).exists
    }

    override suspend fun nicknameExists(nickname: String): Result<Boolean> = call {
        api.nicknameExists(nickname.normalized()).exists
    }

    override suspend fun login(email: String, password: String): Result<AuthSession> = call {
        api.login(LoginRequestDto(email = email.normalized(), password = password)).toSession()
    }

    override suspend fun sendSignupCode(email: String): Result<Unit> = call {
        api.sendSignupCode(SendCodeRequestDto(email.normalized()))
    }

    override suspend fun verifySignupCode(email: String, code: String): Result<Unit> = call {
        // 서버가 `{"verified": true}` 를 주지만 실패는 4xx 로 온다 — 본문을 따로 안 본다
        api.verifySignupCode(VerifyCodeRequestDto(email = email.normalized(), code = code.trim()))
        Unit
    }

    override suspend fun signup(
        email: String,
        password: String,
        nickname: String,
        marketingAgreed: Boolean,
        ageOver14: Boolean,
    ): Result<AuthSession> = call {
        api.signup(
            SignupRequestDto(
                email = email.normalized(),
                password = password,
                nickname = nickname.normalized(),
                // 필수 2종은 화면이 이미 막았다. 서버도 `400 AGREEMENT_REQUIRED` 로 다시 본다
                agreements = AgreementsRequestDto(
                    tos = true,
                    privacy = true,
                    marketing = marketingAgreed,
                ),
                ageOver14 = ageOver14,
            ),
        ).toSession()
    }

    /**
     * 두 모양을 [KakaoLoginOutcome] 으로 가른다. (§1-7)
     *
     * **`isNewUser` 만 믿지 않는다.** 기존 가입자라는데 토큰이 없으면 계약 위반이라,
     * 화면에 "로그인됐다" 를 그리는 대신 실패로 올린다 — 세션 없이 홈으로 보내면
     * 다음 요청마다 401 이 나고 사용자는 이유를 모른다.
     */
    override suspend fun kakaoLogin(kakaoAccessToken: String): Result<KakaoLoginOutcome> = call {
        val dto = api.kakaoLogin(KakaoLoginRequestDto(kakaoAccessToken = kakaoAccessToken))
        if (dto.isNewUser) {
            KakaoLoginOutcome.NewUser(
                kakaoAccessToken = kakaoAccessToken,
                nickname = dto.kakaoProfile?.nickname,
                email = dto.kakaoProfile?.email,
            )
        } else {
            KakaoLoginOutcome.Session(dto.toSession())
        }
    }

    override suspend fun kakaoSignup(
        kakaoAccessToken: String,
        nickname: String,
        marketingAgreed: Boolean,
        ageOver14: Boolean,
    ): Result<AuthSession> = call {
        api.kakaoSignup(
            KakaoSignupRequestDto(
                kakaoAccessToken = kakaoAccessToken,
                nickname = nickname.normalized(),
                // 필수 2종은 화면이 이미 막았다. 서버도 `400 AGREEMENT_REQUIRED` 로 다시 본다
                agreements = AgreementsRequestDto(
                    tos = true,
                    privacy = true,
                    marketing = marketingAgreed,
                ),
                ageOver14 = ageOver14,
            ),
        ).toSession()
    }

    override suspend fun requestPasswordReset(email: String): Result<Unit> = call {
        api.requestPasswordReset(ResetRequestDto(email.normalized()))
    }

    override suspend fun logout(refreshToken: String): Result<Unit> = call {
        tokenApi.logout(LogoutRequestDto(refreshToken))
    }

    /**
     * 예외를 `Result` 로 옮긴다.
     *
     * 이 인터페이스만 `Result` 를 쓰는 이유는 [AuthRepository] KDoc 에 적어 두었다.
     *
     * **먼저 [apiCall] 로 감싼다**(#106 리뷰). Retrofit 이 던지는 것은 `HttpException` ·
     * `IOException` 이지 `ApiException` 이 아니다. 바로 `catch (e: ApiException)` 만 두면
     * 그 둘이 catch 를 지나가고, 호출부는 `viewModelScope.launch` 안이라 **잡히지 않은 예외로
     * 앱이 죽는다.** `apiCall` 이 우리 계약(`ApiException`)으로 바꿔 준 뒤에 담는다.
     */
    private suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(apiCall(block))
    } catch (e: ApiException) {
        Result.failure(e)
    }

    /** 앞뒤 공백만 없앤다. 소문자화는 서버가 한다(이슈 #97). */
    private fun String.normalized(): String = trim()
}

/**
 * 카카오 로그인 응답을 세션으로. **기존 가입자일 때만 부른다.** (§1-7)
 *
 * 토큰과 사용자가 nullable 인 것은 미가입 응답을 같은 DTO 로 받기 때문이다. 여기까지
 * 왔다는 건 `isNewUser=false` 라는 뜻이라 셋 다 있어야 한다.
 *
 * **없으면 실패로 올린다.** 세션 없이 홈으로 보내면 다음 요청마다 401 이 나고 사용자는
 * 이유를 모른다 — "로그인은 됐는데 아무것도 안 되는" 상태가 제일 나쁘다.
 */
private fun KakaoLoginResponseDto.toSession(): AuthSession {
    val access = accessToken
    val refresh = refreshToken
    val member = user
    if (access == null || refresh == null || member == null) {
        throw ApiException.Malformed(
            IllegalStateException("isNewUser=false 인데 토큰·사용자가 없다 (§1-7)"),
        )
    }
    return AuthSession(
        tokens = AuthTokens(accessToken = access, refreshToken = refresh),
        profile = SessionProfile(
            nickname = member.nickname,
            email = member.email,
            loginProvider = LoginProvider.entries.firstOrNull { it.name == member.loginProvider }
                ?: throw ApiException.Malformed(
                    IllegalArgumentException("모르는 loginProvider: ${member.loginProvider}"),
                ),
            // **약관은 이 응답에 없다.** `user` 는 요약이라(§1-7) 여기서 값을 지어내면
            // 서버가 ON 인 사용자에게 OFF 를 보여 준다(#287). `GET /me` 가 채운다
            marketingAgreed = null,
        ),
    )
}

/** 로그인·가입 응답을 세션으로. 셋이 같은 모양이라 하나로 받는다(§1-5 · §1-6 · §1-8). */
private fun AuthTokenResponseDto.toSession(): AuthSession = AuthSession(
    tokens = AuthTokens(accessToken = accessToken, refreshToken = refreshToken),
    profile = SessionProfile(
        nickname = user.nickname,
        // KAKAO 가입자는 이메일이 없을 수 있다. 화면이 행을 숨긴다(§2 · #59)
        email = user.email,
        loginProvider = LoginProvider.entries.firstOrNull { it.name == user.loginProvider }
            ?: throw IllegalArgumentException("모르는 loginProvider: ${user.loginProvider}"),
        // **약관은 이 응답에 없다** — 위 카카오 매퍼와 같은 이유다(#287)
        marketingAgreed = null,
    ),
)
