package com.runninggu.app.data.repository

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.httpErrorOf
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
/**
 * `POST /auth/kakao` 의 두 결말. (API 명세 §1-7 · SPEC 결정-22 개정 · 이슈 #206)
 *
 * 서버가 **한 `200` 으로 두 가지를 돌려준다.** 기존 KAKAO 가입자면 토큰이, 미가입이면
 * 프로필이 온다. `Result<AuthSession>` 으로는 담을 수 없어서 타입으로 가른다.
 *
 * **화면이 `isNewUser` 를 직접 보지 않게 하는 것이 요점이다.** 불리언을 넘기면 "true 인데
 * 토큰을 읽는" 조합이 만들어지고, 그건 컴파일이 잡아 주지 않는다. `when` 이 두 갈래를
 * 강제하면 그 실수가 성립하지 않는다([ReauthCredential] 과 같은 이유 · #198 리뷰).
 *
 * DTO 를 그대로 올리지 않는 이유는 AGENTS 2장이다 — `ui` 는 `data/remote` 를 모른다.
 */
sealed interface KakaoLoginOutcome {

    /** 기존 KAKAO 계정. 바로 홈으로 간다. */
    data class Session(val session: AuthSession) : KakaoLoginOutcome

    /**
     * 미가입. A2 약관·닉네임 화면으로 보낸다.
     *
     * **둘 다 null 일 수 있다.** 카카오 동의 항목에 따라 닉네임도 이메일도 안 올 수 있다
     * (§1-7 · §4.1). 가입 화면의 **초기값으로만** 쓰고, 없으면 사용자가 직접 넣는다.
     *
     * [kakaoAccessToken] 을 함께 들고 간다 — [AuthRepository.kakaoSignup] 이 같은 토큰을
     * 다시 요구하는데, 화면이 따로 보관하면 어디에 뒀는지가 화면마다 달라진다.
     */
    data class NewUser(
        val kakaoAccessToken: String,
        val nickname: String?,
        val email: String?,
    ) : KakaoLoginOutcome
}

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
        ageOver14: Boolean,
    ): Result<AuthSession>

    /**
     * `POST /auth/kakao`. 카카오 액세스 토큰으로 로그인하거나 가입 화면으로 보낸다. (§1-7)
     *
     * **한 `200` 이 두 가지를 뜻한다.** 기존 KAKAO 가입자면 토큰이 오고, 미가입이면
     * `isNewUser=true` 와 프로필이 온다 — 상태 코드로는 구분되지 않는다. 그래서
     * [KakaoLoginOutcome] 으로 갈라서 돌려준다.
     *
     * 기본 구현이 예외를 던진다 — 조용히 실패하면 화면이 "로그인됐다" 로 그린다.
     */
    suspend fun kakaoLogin(kakaoAccessToken: String): Result<KakaoLoginOutcome> =
        throw UnsupportedOperationException("이 구현은 카카오 로그인을 하지 않는다 (§1-7)")

    /**
     * `POST /auth/kakao/signup`. 카카오 신규 가입. (§1-8)
     *
     * **이메일 인증을 거치지 않는다** — 카카오가 이미 확인한 계정이다. 응답은 §1-5 와
     * 같아서 가입이 곧 로그인이다.
     *
     * @param nickname 사용자가 A2 에서 확정한 값. 카카오가 준 것을 그대로 보내지 않는다 —
     *  없을 수도 있고(동의 항목), 중복일 수도 있어서 화면이 한 번 받는다
     */
    suspend fun kakaoSignup(
        kakaoAccessToken: String,
        nickname: String,
        marketingAgreed: Boolean,
        ageOver14: Boolean,
    ): Result<AuthSession> =
        throw UnsupportedOperationException("이 구현은 카카오 가입을 하지 않는다 (§1-8)")

    /** `POST /auth/password/reset-request`. 가입 여부와 무관하게 `202`(§4.3 계정 존재 비노출). */
    suspend fun requestPasswordReset(email: String): Result<Unit>

    /**
     * `POST /auth/logout` — 그 리프레시를 revoke 한다. (§1-10 · 이슈 #113)
     *
     * **인증자 없는 클라이언트로 나간다**([TokenApi]). 이유는 그쪽 KDoc 에 있다.
     *
     * 실패를 삼키지 않는다 — **서버가 지웠는지 모르는 채로 로컬만 지우면** 사용자는
     * 로그아웃했다고 믿는데 서버 세션이 남는다. 화면이 결과를 보고 정한다.
     */
    suspend fun logout(refreshToken: String): Result<Unit>
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
        ageOver14: Boolean,
    ): Result<AuthSession> {
        delay(NETWORK_DELAY_MS)
        offlineOrNull<AuthSession>(email)?.let { return it }
        // 스텁도 서버와 같은 자리에서 막는다 — 가짜로 돌릴 때만 통과하면 화면이 거짓말을 한다
        if (!ageOver14) return Result.failure(httpErrorOf(400, null))
        return Result.success(fakeSession(email, nickname, marketingAgreed))
    }

    override suspend fun requestPasswordReset(email: String): Result<Unit> {
        delay(NETWORK_DELAY_MS)
        offlineOrNull<Unit>(email)?.let { return it }
        return Result.success(Unit)
    }

    /** 스텁은 언제나 성공한다. 서버가 없으니 revoke 할 것도 없다. */
    override suspend fun logout(refreshToken: String): Result<Unit> {
        delay(NETWORK_DELAY_MS)
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

/** 통신 자체가 안 된 경우. 화면 문구를 서버 오류와 갈라야 한다. */
internal fun Throwable.isNetworkFailure(): Boolean = this is ApiException.Network
