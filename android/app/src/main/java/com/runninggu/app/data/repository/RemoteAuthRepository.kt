package com.runninggu.app.data.repository

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.AuthApi
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.dto.AgreementsRequestDto
import com.runninggu.app.data.remote.dto.AuthTokenResponseDto
import com.runninggu.app.data.remote.dto.LoginRequestDto
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
class RemoteAuthRepository(private val api: AuthApi) : AuthRepository {

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
            ),
        ).toSession()
    }

    override suspend fun requestPasswordReset(email: String): Result<Unit> = call {
        api.requestPasswordReset(ResetRequestDto(email.normalized()))
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

/** 로그인·가입 응답을 세션으로. 셋이 같은 모양이라 하나로 받는다(§1-5 · §1-6 · §1-8). */
private fun AuthTokenResponseDto.toSession(): AuthSession = AuthSession(
    tokens = AuthTokens(accessToken = accessToken, refreshToken = refreshToken),
    profile = SessionProfile(
        nickname = user.nickname,
        // KAKAO 가입자는 이메일이 없을 수 있다. 화면이 행을 숨긴다(§2 · #59)
        email = user.email,
        loginProvider = LoginProvider.entries.firstOrNull { it.name == user.loginProvider }
            ?: throw IllegalArgumentException("모르는 loginProvider: ${user.loginProvider}"),
    ),
)
