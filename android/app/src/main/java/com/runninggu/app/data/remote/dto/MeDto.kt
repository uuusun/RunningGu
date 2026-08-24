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
