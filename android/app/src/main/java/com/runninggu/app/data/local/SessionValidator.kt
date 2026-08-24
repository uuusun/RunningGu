package com.runninggu.app.data.local

/**
 * 복원한 세션이 아직 살아 있는지 서버에 물어본 결과. (`docs/screen-api-matrix.md` A0)
 *
 * A0 계약이 **"DataStore 값 + `GET /api/me`"** 다. 디스크에 토큰이 남아 있다는 것과 그 토큰이
 * 아직 쓸 수 있다는 것은 다르다 — 다른 기기에서 탈퇴했거나 비밀번호를 바꿨으면 이미 죽은
 * 토큰인데, 안 물어보면 **로그인 상태로 열렸다가 첫 요청부터 전부 실패**한다(#89 리뷰).
 */
sealed interface SessionValidation {

    /** 살아 있다. 프로필은 **서버 것이 기준**이라 그대로 갈아끼운다(SPEC §9.3 SSOT). */
    data class Valid(val profile: SessionProfile) : SessionValidation

    /** 죽었다. 재발급까지 시도한 뒤의 `401` 이라 재로그인 말고는 방법이 없다. */
    data object Expired : SessionValidation

    /**
     * 못 물어봤다. 네트워크·서버 오류다.
     *
     * **이때는 세션을 지킨다.** 지하철에서 앱을 켰다고 로그아웃되면 안 된다 — 토큰이 정말
     * 죽었다면 다음 요청이 `401` 을 받고 그때 정리된다(#74 의 `TokenAuthenticator`).
     */
    data object Unknown : SessionValidation
}

/**
 * 세션 검증 창구. `GET /api/me` 한 번이다.
 *
 * [SessionStore] 가 이 인터페이스만 보게 해서, 세션 로직을 기기 없이 테스트할 수 있게 한다.
 */
fun interface SessionValidator {
    suspend fun validate(): SessionValidation
}

/**
 * 물어보지 않는 구현. **테스트·미리보기용 기본값**이다.
 *
 * 운영은 [SessionStore.bind] 에 `ServiceLocator.sessionValidator`(`GET /api/me`)를 넘긴다 —
 * 여기로 떨어지면 검증 없이 디스크 값만 믿는 것이라, 실수로 기본값이 쓰이면 A0 이 사라진다.
 */
val NoSessionValidator = SessionValidator { SessionValidation.Unknown }
