package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * `GET /api/me` 응답. (API 명세 §2)
 *
 * 명세가 **"앱 시작 세션 검증 겸용"** 이라고 못 박은 그 응답이다 — A0 이 이걸로 복원한
 * 토큰이 아직 살아 있는지 확인한다.
 *
 * **필수 필드에 기본값을 두지 않는다.** 계약상 항상 오는 값에 기본값을 두면, 서버가
 * 빠뜨렸을 때 `null`·`false` 로 숨겨져 조용히 지나간다 — 마케팅 동의가 `false` 로 보이면
 * 사용자가 켠 적 없는 것처럼 되고, 토글을 눌러야 맞춰지는데 그러면 실제로는 철회가 된다
 * (#89 리뷰). `email` 은 **키는 항상 오되 값이 null 일 수 있다**(§2).
 */
@Serializable
data class MeDto(
    val id: Long,
    /**
     * **키는 항상 오지만 값이 null 일 수 있다**(§2).
     *
     * EMAIL 가입자는 서버가 정규화한 이메일, KAKAO 가입자는 카카오가 준 스냅샷이거나 null 이다.
     * null 이면 화면이 이메일 행을 숨긴다 — placeholder 를 두지 않는다(#59 확정).
     */
    val email: String?,
    val nickname: String,
    /** `EMAIL|KAKAO`. 한 사용자는 하나만 갖는다(결정-22 개정). */
    val loginProvider: String,
    val agreements: AgreementsDto,
)

/** 약관 동의 상태. (§2) */
@Serializable
data class AgreementsDto(
    val tos: Boolean,
    val privacy: Boolean,
    /** 계정 관리 화면 토글의 초기값이다. */
    val marketing: Boolean,
)

/** `PATCH /api/me` 요청 본문. (§2) */
@Serializable
data class UpdateNicknameRequest(val nickname: String)

/**
 * `PATCH /api/me/agreements` 요청 본문. (§2)
 *
 * **필수 약관은 여기로 못 바꾼다** — 철회는 탈퇴 절차로 안내한다(§2).
 */
@Serializable
data class UpdateMarketingRequest(val marketing: Boolean)

/**
 * `PUT /api/me/password` 요청 본문. (§2-1)
 *
 * **EMAIL 로그인 수단에서만 부른다.** KAKAO 가입자는 비밀번호 자체가 없어 화면이 메뉴를
 * 노출하지 않는다(§2-1 · D-28).
 */
@Serializable
data class PasswordChangeRequest(val currentPassword: String, val newPassword: String)

/**
 * `PUT /api/me/password` 응답 — **새 token pair**. (§2-1)
 *
 * 서버가 한 트랜잭션에서 비밀번호를 바꾸고 **기존 refresh 를 전부 revoke** 한 뒤 현재
 * 기기용으로 다시 발급한다. 그래서 프로필이 아니라 토큰이 온다 — `PATCH /me` 셋과 모양이
 * 다른 유일한 자리다.
 *
 * `POST /auth/refresh` 응답과 모양이 같지만 **따로 둔다.** 이름이 자기 자리를 말해야 하고,
 * 두 계약이 각자 움직일 수 있다. 합치는 것은 명세가 "동일 응답" 이라고 못 박았을 때다 —
 * `AuthTokenResponseDto` 가 §1-5·1-6·1-8 을 하나로 둔 것이 그 경우다.
 */
@Serializable
data class PasswordChangeResponseDto(val accessToken: String, val refreshToken: String)

/**
 * `POST /api/me/reauth` 요청 본문. (§2-2 · D-23)
 *
 * **가입한 수단과 같은 것으로만 재인증한다.** EMAIL 은 현재 비밀번호, KAKAO 는 SDK 가
 * 방금 발급한 액세스 토큰이다. 다른 수단을 보내면 `409 REAUTH_PROVIDER_MISMATCH` 다.
 *
 * 한 계정은 수단을 하나만 갖는다(결정-22 개정). 그래서 두 필드가 다 nullable 이고 둘 중
 * 하나만 채운다 — 화면이 `loginProvider` 를 보고 고른다.
 */
@Serializable
data class ReauthRequest(
    /** `EMAIL` 또는 `KAKAO`. */
    val provider: String,
    val password: String? = null,
    val kakaoAccessToken: String? = null,
)

/**
 * `POST /api/me/reauth` 응답 — **탈퇴 전용 5분 토큰**. (§2-2)
 *
 * `DELETE /api/me` 의 `X-Reauth-Token` 헤더로만 쓴다. 다른 요청에 붙이지 않는다.
 *
 * **로그에 남기지 않는다**(명세 명시 · AGENTS 8장). 액세스 토큰과 같은 급으로 다룬다.
 */
@Serializable
data class ReauthResponseDto(val reauthToken: String, val expiresInSec: Int = 0)
