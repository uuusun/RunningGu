package com.runninggu.app.ui.auth

import kotlinx.coroutines.delay

/**
 * 인증 API 창구. (API 명세 §1 · SPEC §4.1~4.3)
 *
 * 성공/실패만 화면에 필요하므로 토큰 저장은 여기서 다루지 않는다 — 세션 영속(DataStore)과
 * 토큰 관리는 AP-14 연동에서 붙는다.
 *
 * TODO(AP-14): `data/remote` 의 Retrofit 구현으로 교체한다 (`POST /auth/login` 등 §1).
 */
interface AuthRepository {
    /** `POST /auth/login`. 실패 사유는 구분하지 않는다 — 계정 존재를 노출하지 않는다(§4.1). */
    suspend fun login(email: String, password: String): Result<Unit>

    /** `POST /auth/email/send-code`. 쿨다운(60초)·중복 가입 오류를 던질 수 있다. */
    suspend fun sendSignupCode(email: String): Result<Unit>

    /** `POST /auth/email/verify`. 6자리 코드 검증. */
    suspend fun verifySignupCode(email: String, code: String): Result<Unit>

    /** `POST /auth/signup`. 성공 시 자동 로그인된 것으로 본다 (명세 §1-5 — 201이 로그인 응답). */
    suspend fun signup(
        email: String,
        password: String,
        nickname: String,
        marketingAgreed: Boolean,
    ): Result<Unit>

    /** `POST /auth/password/reset-request`. 가입 여부와 무관하게 성공한다 (§4.3 계정 존재 비노출). */
    suspend fun requestPasswordReset(email: String): Result<Unit>
}

/**
 * 백엔드 인증 API가 붙기 전까지 쓰는 스텁.
 *
 * 데모 규칙 — 화면 상태를 다 볼 수 있게 실패 경로도 재현한다.
 * - 로그인: 비밀번호가 `wrong…` 으로 시작하면 실패, 그 외 성공
 * - 인증 코드: 명세 예시값 [SAMPLE_CODE] 만 성공
 */
object FakeAuthRepository : AuthRepository {

    /** 명세 §1-4 예시 코드. 데모에서 이 값을 입력하면 통과한다. */
    const val SAMPLE_CODE = "483920"

    override suspend fun login(email: String, password: String): Result<Unit> {
        delay(NETWORK_DELAY_MS)
        return if (password.startsWith("wrong")) {
            Result.failure(IllegalStateException("LOGIN_FAILED"))
        } else {
            Result.success(Unit)
        }
    }

    override suspend fun sendSignupCode(email: String): Result<Unit> {
        delay(NETWORK_DELAY_MS)
        return Result.success(Unit)
    }

    override suspend fun verifySignupCode(email: String, code: String): Result<Unit> {
        delay(NETWORK_DELAY_MS)
        return if (code == SAMPLE_CODE) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("INVALID_CODE"))
        }
    }

    override suspend fun signup(
        email: String,
        password: String,
        nickname: String,
        marketingAgreed: Boolean,
    ): Result<Unit> {
        delay(NETWORK_DELAY_MS)
        return Result.success(Unit)
    }

    override suspend fun requestPasswordReset(email: String): Result<Unit> {
        delay(NETWORK_DELAY_MS)
        return Result.success(Unit)
    }

    private const val NETWORK_DELAY_MS = 400L
}
