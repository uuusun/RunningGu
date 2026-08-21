package com.runninggu.app.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * `GET /api/me` 응답. (API 명세 §2)
 *
 * 명세가 **"앱 시작 세션 검증 겸용"** 이라고 못 박은 그 응답이다 — A0 이 이걸로 복원한
 * 토큰이 아직 살아 있는지 확인한다.
 *
 * `id` · `nickname` · `loginProvider` 에 **기본값을 두지 않는다.** 필수인데 안 오면 조용히
 * 통과시키지 않고 파싱에서 터지게 한다.
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
    val email: String? = null,
    val nickname: String,
    /** `EMAIL|KAKAO`. 한 사용자는 하나만 갖는다(결정-22 개정). */
    val loginProvider: String,
    val agreements: AgreementsDto = AgreementsDto(),
)

/** 약관 동의 상태. (§2) */
@Serializable
data class AgreementsDto(
    val tos: Boolean = false,
    val privacy: Boolean = false,
    /** 계정 관리 화면 토글의 초기값이다. */
    val marketing: Boolean = false,
)
