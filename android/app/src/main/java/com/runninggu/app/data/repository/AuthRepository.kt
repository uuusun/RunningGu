package com.runninggu.app.data.repository

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import kotlinx.coroutines.delay

/**
 * 인증 API 창구. (API 명세 §1 · SPEC §4.1~4.3)
 *
 * 실패는 `Result.failure(ApiException)` 으로 준다 — `data/remote` 가 쓰는 것과 **같은 예외**라
 * (#43 규약) Retrofit 구현으로 바꿔도 화면 분기가 그대로 산다. 화면은 `ApiException.Http.code`
 * 로 사유를 갈라 문구를 고른다(예: `CODE_EXPIRED` 는 재발송을 유도해야 한다).
 *
 * 구현이 둘이다 — [RemoteAuthRepository](Retrofit)와 [FakeAuthRepository](서버 전 데모용).
 * 화면은 인터페이스만 보므로 [com.runninggu.app.data.ServiceLocator] 에서 갈아끼운다.
 *
 * **실패를 `Result` 로 주는 것은 여기뿐이다.** `data/` 의 다른 저장소들은 `ApiException` 을
 * 던진다. 규칙이 섞여 있는 게 맞고, 맞추려면 화면 세 곳의 분기를 다시 써야 해서 이 PR 에서
 * 하지 않았다 — 별도로 정리한다(#97 · 앱 UI 담당과 함께).
 */
interface AuthRepository {

    /**
     * `GET /auth/email/exists`. 가입 2단계 인라인 검증용. (§1-1 · 이슈 #97 선택지 A)
     *
     * 실패해도 다음 단계를 막지 않는다 — 확인 API 가 죽었다고 가입 자체를 못 하면
     * 사용자가 할 수 있는 게 없다. `send-code` 의 `409` 가 최종 방어다.
     */
    suspend fun emailExists(email: String): Result<Boolean>

    /** `GET /auth/nickname/exists`. 대소문자 무시 판정은 서버가 한다. (§1-2 · 이슈 #97) */
    suspend fun nicknameExists(nickname: String): Result<Boolean>

    /** `POST /auth/login`. 실패 사유는 구분하지 않는다 — 계정 존재를 노출하지 않는다(§4.1). */
    suspend fun login(email: String, password: String): Result<AuthSession>

    /** `POST /auth/email/send-code`. 쿨다운 위반은 `429 SEND_COOLDOWN`. */
    suspend fun sendSignupCode(email: String): Result<Unit>

    /**
     * `POST /auth/email/verify`. 6자리 코드 검증.
     *
     * 실패 코드가 셋이고 사용자가 할 일이 다르다(§1-4 · NFR-10 🔒).
     * `INVALID_CODE` 는 다시 입력, `CODE_EXPIRED` · `TOO_MANY_ATTEMPTS` 는 **재발송**이다.
     */
    suspend fun verifySignupCode(email: String, code: String): Result<Unit>

    /** `POST /auth/signup`. `201` 이 곧 로그인 응답이다(§1-5) — 토큰과 사용자를 함께 준다. */
    suspend fun signup(
        email: String,
        password: String,
        nickname: String,
        marketingAgreed: Boolean,
    ): Result<AuthSession>

    /** `POST /auth/password/reset-request`. 가입 여부와 무관하게 `202`(§4.3 계정 존재 비노출). */
    suspend fun requestPasswordReset(email: String): Result<Unit>
}

/**
 * 로그인·가입 성공 결과. (API 명세 §1-5 · §1-6)
 *
 * **토큰만 주지 않고 사용자도 함께 준다.** 예전에는 토큰만 돌려줘서 화면이 닉네임을
 * 이메일 앞부분에서 파생했는데(`runner@test.com` → "runner"), 그건 서버가 아는 진짜
 * 닉네임이 아니다. 마이 화면과 카드에 다른 이름이 보이게 된다.
 */
data class AuthSession(val tokens: AuthTokens, val profile: SessionProfile)

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

    /** 데모에서 "이미 가입된 이메일" 화면을 볼 수 있게 정해 둔 값. */
    const val TAKEN_EMAIL = "taken@test.com"

    /** 데모에서 "이미 사용 중인 닉네임" 화면을 볼 수 있게 정해 둔 값. */
    const val TAKEN_NICKNAME = "김러너"

    /** NFR-10 🔒 — 5회 실패하면 재발송부터 다시 해야 한다. */
    const val MAX_VERIFY_ATTEMPTS = 5

    /** 이메일별 코드 오입력 횟수. 재발송하면 0으로 돌아간다. */
    private val attempts = mutableMapOf<String, Int>()

    override suspend fun emailExists(email: String): Result<Boolean> {
        delay(NETWORK_DELAY_MS)
        offlineOrNull<Boolean>(email)?.let { return it }
        // 데모에서 중복 화면을 볼 수 있게 한 값을 정해 둔다
        return Result.success(email.trim().equals(TAKEN_EMAIL, ignoreCase = true))
    }

    override suspend fun nicknameExists(nickname: String): Result<Boolean> {
        delay(NETWORK_DELAY_MS)
        return Result.success(nickname.trim().equals(TAKEN_NICKNAME, ignoreCase = true))
    }

    override suspend fun login(email: String, password: String): Result<AuthSession> {
        delay(NETWORK_DELAY_MS)
        offlineOrNull<AuthSession>(email)?.let { return it }
        return if (password.startsWith("wrong")) {
            failure(401, ApiErrorCode.LOGIN_FAILED)
        } else {
            Result.success(fakeSession(email, nickname = email.substringBefore('@')))
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
    ): Result<AuthSession> {
        delay(NETWORK_DELAY_MS)
        offlineOrNull<AuthSession>(email)?.let { return it }
        return Result.success(fakeSession(email, nickname, marketingAgreed))
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

    private fun fakeSession(
        email: String,
        nickname: String,
        marketingAgreed: Boolean = false,
    ) = AuthSession(
        tokens = AuthTokens(
            accessToken = "fake-access-token",
            refreshToken = "fake-refresh-token",
        ),
        profile = SessionProfile(
            nickname = nickname.trim(),
            email = email.trim(),
            loginProvider = LoginProvider.EMAIL,
            marketingAgreed = marketingAgreed,
        ),
    )

    private const val NETWORK_DELAY_MS = 400L
}

/** 실패에서 서버 에러 코드를 꺼낸다. 네트워크·해석 실패면 null. */
internal fun Throwable.apiErrorCode(): ApiErrorCode? = (this as? ApiException.Http)?.code

/** 통신 자체가 안 된 경우. 화면 문구를 서버 오류와 갈라야 한다. */
internal fun Throwable.isNetworkFailure(): Boolean = this is ApiException.Network
