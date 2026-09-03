package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * 인증 요청·응답. (API 명세 §1)
 *
 * **이메일 정규화는 서버가 한다**(이슈 #97 합의). 앱은 앞뒤 공백만 다듬어 보내고 소문자로
 * 바꾸지 않는다 — 규칙이 두 벌이면 중복 확인은 통과했는데 가입에서 `409` 가 나는 식으로
 * 갈라진다. 다듬는 자리는 저장소 하나로 모은다.
 */

/** `POST /auth/email/send-code` 요청. `204`. (§1-3) */
@Serializable
data class SendCodeRequestDto(val email: String)

/** `POST /auth/email/verify` 요청. (§1-4) */
@Serializable
data class VerifyCodeRequestDto(val email: String, val code: String)

/**
 * `POST /auth/email/verify` 응답. (§1-4)
 *
 * `verified` 에 기본값을 두지 않는다 — 필수인데 안 오면 조용히 `false` 로 통과시키지 않고
 * 파싱에서 터지게 한다.
 */
@Serializable
data class VerifyCodeResponseDto(val verified: Boolean)

/** 약관 동의. 필수 2종 + 선택 마케팅. (§1-5 · NFR-12) */
@Serializable
data class AgreementsRequestDto(
    val tos: Boolean,
    val privacy: Boolean,
    val marketing: Boolean = false,
)

/** `POST /auth/signup` 요청. `201` 응답은 로그인과 같다(자동 로그인). (§1-5) */
@Serializable
data class SignupRequestDto(
    val email: String,
    val password: String,
    val nickname: String,
    val agreements: AgreementsRequestDto,
    /**
     * 만 14세 이상 확인. **최상위 필수 필드다** (SPEC §4.2-1 · 결정-58).
     *
     * `agreements` 안에 넣지 않는다 — 명세가 **"전체 동의 밖의 별도 필수 항목"** 으로
     * 못 박았고, 서버도 최상위에서 받는다. 누락은 `400 VALIDATION_FAILED`,
     * `false` 는 `400 AGE_REQUIREMENT_NOT_MET` 이다.
     *
     * **기본값을 두지 않는다.** `true` 로 메우면 앱이 사용자에게 묻지도 않고 만 14세
     * 이상이라고 대신 답하는 것이 된다.
     */
    val ageOver14: Boolean,
)

/** `POST /auth/login` 요청. (§1-6) */
@Serializable
data class LoginRequestDto(val email: String, val password: String)

/**
 * 로그인·가입 성공 응답. (§1-5 · §1-6 · §1-8)
 *
 * 셋이 **같은 모양**이라 하나로 둔다 — 명세가 "1-6 과 동일 응답" 이라고 못 박았다.
 */
@Serializable
data class AuthTokenResponseDto(
    val accessToken: String,
    val refreshToken: String,
    val user: AuthUserDto,
)

/**
 * 응답에 실려 오는 사용자. (§1-6)
 *
 * `email` 은 nullable 이다 — KAKAO 가입자는 카카오가 이메일을 안 줬을 수 있다(§2 · #59).
 * 화면은 null 이면 이메일 행을 숨기고 placeholder 를 두지 않는다.
 */
@Serializable
data class AuthUserDto(
    val id: Long,
    val email: String? = null,
    val nickname: String,
    /** `EMAIL|KAKAO`. 한 사용자는 하나만 갖는다(결정-22 개정). */
    val loginProvider: String,
)

/** `POST /auth/kakao` 요청. (§1-7) */
@Serializable
data class KakaoLoginRequestDto(val kakaoAccessToken: String)

/**
 * `POST /auth/kakao` 응답. **두 모양이 한 `200` 으로 온다.** (§1-7)
 *
 * 기존 가입자면 토큰이, 미가입이면 `isNewUser=true` 와 프로필이 온다. 그래서 두 쪽 모두
 * nullable 이고, 어느 쪽인지는 [isNewUser] 로 가른다 — 상태 코드로는 구분되지 않는다.
 */
@Serializable
data class KakaoLoginResponseDto(
    val isNewUser: Boolean = false,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val user: AuthUserDto? = null,
    val kakaoProfile: KakaoProfileDto? = null,
)

/** 카카오가 준 프로필 스냅샷. 이메일은 동의 항목에 따라 없을 수 있다. (§1-7) */
@Serializable
data class KakaoProfileDto(val nickname: String? = null, val email: String? = null)

/** `POST /auth/kakao/signup` 요청. 이메일 인증을 생략한다. (§1-8) */
@Serializable
data class KakaoSignupRequestDto(
    val kakaoAccessToken: String,
    val nickname: String,
    val agreements: AgreementsRequestDto,
    /** 만 14세 이상 확인. 이메일 가입과 같은 계약이다 (SPEC §4.2-1 · 결정-58). */
    val ageOver14: Boolean,
)

/** `POST /auth/logout` 요청. 해당 리프레시만 revoke 한다. (§1-10) */
@Serializable
data class LogoutRequestDto(val refreshToken: String)

/** `POST /auth/password/reset-request` 요청. **가입 여부와 무관하게 `202`** 다. (§1-11) */
@Serializable
data class ResetRequestDto(val email: String)

/** `POST /auth/password/reset` 요청. 링크로 받은 토큰을 그대로 보낸다. (§1-12) */
@Serializable
data class ResetPasswordRequestDto(val token: String, val newPassword: String)

/** `GET /auth/email/exists` · `/auth/nickname/exists` 응답. (§1-1 · §1-2) */
@Serializable
data class ExistsResponseDto(val exists: Boolean)
