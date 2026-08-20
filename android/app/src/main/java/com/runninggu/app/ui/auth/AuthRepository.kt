package com.runninggu.app.ui.auth

import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import kotlinx.coroutines.delay

/**
 * 로그인·가입 성공 시 받는 토큰 쌍. (API 명세 §1-5 · §1-6)
 *
 * 액세스 30분 · 리프레시 14일이고 리프레시는 회전한다(§0-2). 앱은 두 값을 원자적으로
 * 교체해야 하므로 하나로 묶어 다룬다.
 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
)

/**
 * 인증 API 창구. (API 명세 §1 · SPEC §4.1~4.3)
 *
 * 실패는 `Result.failure(ApiException)` 으로 준다 — `data/remote` 가 쓰는 것과 **같은 예외**라
 * (#43 규약) Retrofit 구현으로 바꿔도 화면 분기가 그대로 산다. 화면은 `ApiException.Http.code`
 * 로 사유를 갈라 문구를 고른다(예: `CODE_EXPIRED` 는 재발송을 유도해야 한다).
 *
 * TODO(AP-14): `data/remote` 의 Retrofit 구현으로 교체한다. 그때
 *  - `Result` 대신 `apiCall {}` 이 예외를 던지는 형태가 되므로 호출부가 `try/catch` 로 바뀐다
 *  - 발급된 [AuthTokens] 를 `ApiClient.TokenProvider` 에 물려야 인증 API 가 게스트로 나가지 않는다
 */
interface AuthRepository {

    /** `POST /auth/login`. 실패 사유는 구분하지 않는다 — 계정 존재를 노출하지 않는다(§4.1). */
    suspend fun login(email: String, password: String): Result<AuthTokens>

    /** `POST /auth/email/send-code`. 쿨다운 위반은 `429 SEND_COOLDOWN`. */
    suspend fun sendSignupCode(email: String): Result<Unit>

    /**
     * `POST /auth/email/verify`. 6자리 코드 검증.
     *
     * 실패 코드가 셋이고 사용자가 할 일이 다르다(§1-4 · NFR-10 🔒).
     * `INVALID_CODE` 는 다시 입력, `CODE_EXPIRED` · `TOO_MANY_ATTEMPTS` 는 **재발송**이다.
     */
    suspend fun verifySignupCode(email: String, code: String): Result<Unit>

    /** `POST /auth/signup`. `201` 이 곧 로그인 응답이다(§1-5) — 토큰을 함께 준다. */
    suspend fun signup(
        email: String,
        password: String,
        nickname: String,
        marketingAgreed: Boolean,
    ): Result<AuthTokens>

    /** `POST /auth/password/reset-request`. 가입 여부와 무관하게 `202`(§4.3 계정 존재 비노출). */
    suspend fun requestPasswordReset(email: String): Result<Unit>
}

/**
 * 백엔드 인증 API가 붙기 전까지 쓰는 스텁.
 *
 * 화면 상태를 다 볼 수 있게 실패 경로도 재현한다.
 * - 로그인: 비밀번호가 `wrong…` 으로 시작하면 `401 LOGIN_FAILED`
 * - 인증 코드: [SAMPLE_CODE] 만 성공. 틀리면 `400 INVALID_CODE` 이고,
 *   **5회 실패하면 `429 TOO_MANY_ATTEMPTS`** 로 재발송을 요구한다(NFR-10 🔒)
 * - 이메일에 `offline` 이 들어가면 `ApiException.Network` — 통신 실패 화면 확인용
 */
object FakeAuthRepository : AuthRepository {

    /** 명세 §1-4 예시 코드. 데모에서 이 값을 입력하면 통과한다. */
    const val SAMPLE_CODE = "483920"

    /** NFR-10 🔒 — 5회 실패하면 재발송부터 다시 해야 한다. */
    const val MAX_VERIFY_ATTEMPTS = 5

    /** 이메일별 코드 오입력 횟수. 재발송하면 0으로 돌아간다. */
    private val attempts = mutableMapOf<String, Int>()

    override suspend fun login(email: String, password: String): Result<AuthTokens> {
        delay(NETWORK_DELAY_MS)
        offlineOrNull<AuthTokens>(email)?.let { return it }
        return if (password.startsWith("wrong")) {
            failure(401, ApiErrorCode.LOGIN_FAILED)
        } else {
            Result.success(fakeTokens())
        }
    }

    override suspend fun sendSignupCode(email: String): Result<Unit> {
        delay(NETWORK_DELAY_MS)
        offlineOrNull<Unit>(email)?.let { return it }
        attempts[email] = 0 // 재발송하면 시도 횟수가 초기화된다 (§1-4)
        return Result.success(Unit)
    }

    override suspend fun verifySignupCode(email: String, code: String): Result<Unit> {
        delay(NETWORK_DELAY_MS)
        offlineOrNull<Unit>(email)?.let { return it }

        val used = attempts.getOrDefault(email, 0)
        if (used >= MAX_VERIFY_ATTEMPTS) {
            return failure(429, ApiErrorCode.TOO_MANY_ATTEMPTS)
        }
        if (code != SAMPLE_CODE) {
            attempts[email] = used + 1
            // 마지막 기회까지 쓰면 다음 시도부터는 재발송을 요구한다.
            return if (attempts[email]!! >= MAX_VERIFY_ATTEMPTS) {
                failure(429, ApiErrorCode.TOO_MANY_ATTEMPTS)
            } else {
                failure(400, ApiErrorCode.INVALID_CODE)
            }
        }
        attempts.remove(email)
        return Result.success(Unit)
    }

    override suspend fun signup(
        email: String,
        password: String,
        nickname: String,
        marketingAgreed: Boolean,
    ): Result<AuthTokens> {
        delay(NETWORK_DELAY_MS)
        offlineOrNull<AuthTokens>(email)?.let { return it }
        return Result.success(fakeTokens())
    }

    override suspend fun requestPasswordReset(email: String): Result<Unit> {
        delay(NETWORK_DELAY_MS)
        offlineOrNull<Unit>(email)?.let { return it }
        return Result.success(Unit)
    }

    /** 통신 실패 재현 — 이메일에 `offline` 이 들어가면 네트워크 오류를 낸다. */
    private fun <T> offlineOrNull(email: String): Result<T>? =
        if (email.contains("offline")) {
            Result.failure(ApiException.Network(java.io.IOException("fake offline")))
        } else {
            null
        }

    private fun <T> failure(status: Int, code: ApiErrorCode): Result<T> =
        Result.failure(ApiException.Http(status = status, code = code, problem = null))

    private fun fakeTokens() = AuthTokens(
        accessToken = "fake-access-token",
        refreshToken = "fake-refresh-token",
    )

    private const val NETWORK_DELAY_MS = 400L
}

/** 실패에서 서버 에러 코드를 꺼낸다. 네트워크·해석 실패면 null. */
internal fun Throwable.apiErrorCode(): ApiErrorCode? = (this as? ApiException.Http)?.code

/** 통신 자체가 안 된 경우. 화면 문구를 서버 오류와 갈라야 한다. */
internal fun Throwable.isNetworkFailure(): Boolean = this is ApiException.Network
