package com.runninggu.app.data.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 세션 영속. (SPEC §2.2 · NFR-11)
 *
 * 여기가 깨지면 앱을 껐다 켤 때마다 로그인이 풀리거나, 반대로 **로그아웃한 계정으로
 * 되돌아간다.** 뒤쪽이 더 나쁘다 — 남의 기기에 계정이 남는다.
 */
class SessionStoreTest {

    private lateinit var persistence: RecordingPersistence
    private lateinit var scope: CoroutineScope

    private val tokens = AuthTokens(accessToken = "access-1", refreshToken = "refresh-1")
    private val profile = SessionProfile(
        nickname = "민지",
        email = "minji@example.test",
        loginProvider = LoginProvider.EMAIL,
        marketingAgreed = true,
    )

    private lateinit var validator: RecordingValidator

    @Before
    fun setUp() {
        SessionStore.resetForTest()
        persistence = RecordingPersistence()
        validator = RecordingValidator()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private fun bind() = SessionStore.bind(persistence, scope, validator)

    @After
    fun tearDown() {
        scope.cancel()
        SessionStore.resetForTest()
    }

    /** 복원이 끝날 때까지 기다린다. 실제로는 파일 한 번 읽는 시간이다. */
    private fun awaitRestored() = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (!SessionStore.restored.value) delay(POLL_MS)
        }
    }

    /** 저장이 뒤따라 반영될 때까지 기다린다. 쓰기는 비동기라 즉시 보이지 않는다. */
    private fun awaitPersisted(predicate: () -> Boolean) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (!predicate()) delay(POLL_MS)
        }
    }

    @Test
    fun `저장된 세션이 있으면 그대로 올린다`() {
        persistence.stored = PersistedSession(tokens, profile)

        bind()
        awaitRestored()

        assertTrue(SessionStore.isLoggedIn)
        assertEquals(profile, SessionStore.session.value)
        assertEquals(tokens, SessionStore.tokens)
    }

    @Test
    fun `저장된 게 없으면 게스트로 시작한다`() {
        bind()
        awaitRestored()

        assertFalse(SessionStore.isLoggedIn)
        assertNull(SessionStore.tokens)
    }

    @Test
    fun `읽다 터져도 시작 화면은 열린다`() {
        // 여기서 restored 가 안 올라가면 **빈 화면에서 영영 못 나온다.**
        // 파일이 깨졌다고 사용자가 할 수 있는 게 재설치뿐이면 안 된다 (NFR-1)
        persistence.throwOnLoad = true

        bind()
        awaitRestored()

        assertTrue(SessionStore.restored.value)
        assertFalse(SessionStore.isLoggedIn)
    }

    @Test
    fun `저장이 터져도 이번 실행의 로그인은 살아 있다`() {
        persistence.throwOnSave = true
        bind()
        awaitRestored()

        SessionStore.signIn(profile, tokens)

        assertTrue(SessionStore.isLoggedIn)
        assertEquals(tokens, SessionStore.tokens)
    }

    @Test
    fun `로그인하면 저장한다`() {
        bind()
        awaitRestored()

        SessionStore.signIn(profile, tokens)

        awaitPersisted { persistence.stored != null }
        assertEquals(PersistedSession(tokens, profile), persistence.stored)
    }

    @Test
    fun `재발급받은 토큰도 저장한다`() {
        // 안 남기면 다음 실행에서 죽은 액세스 토큰으로 시작해 첫 요청이 401 로 떨어진다
        bind()
        awaitRestored()
        SessionStore.signIn(profile, tokens)
        awaitPersisted { persistence.stored != null }

        val renewed = AuthTokens(accessToken = "access-2", refreshToken = "refresh-2")
        val applied = SessionStore.updateTokens(SessionStore.sessionEpoch, renewed)

        assertTrue(applied)
        awaitPersisted { persistence.stored?.tokens == renewed }
    }

    @Test
    fun `로그아웃하면 디스크에서도 지운다`() {
        bind()
        awaitRestored()
        SessionStore.signIn(profile, tokens)
        awaitPersisted { persistence.stored != null }

        SessionStore.signOut()

        awaitPersisted { persistence.stored == null }
        assertTrue(persistence.cleared)
    }

    @Test
    fun `복원이 늦게 끝나도 그사이 한 로그인을 덮지 않는다`() {
        // 게스트로 둘러보다 로그인한 사람이 이전 계정으로 되돌아가면 안 된다
        val previous = PersistedSession(
            tokens = AuthTokens("old-access", "old-refresh"),
            profile = profile.copy(nickname = "이전계정"),
        )
        persistence.stored = previous
        persistence.loadDelayMs = SLOW_LOAD_MS

        bind()
        SessionStore.signIn(profile, tokens)
        awaitRestored()

        assertEquals("민지", SessionStore.session.value?.nickname)
        assertEquals(tokens, SessionStore.tokens)
    }

    @Test
    fun `복원은 세대를 올리지 않는다`() {
        // 올리면 앱을 켤 때마다 세대가 달라져서, 진행 중이던 재발급 판정 기준이 흔들린다
        persistence.stored = PersistedSession(tokens, profile)
        val before = SessionStore.sessionEpoch

        bind()
        awaitRestored()

        assertEquals(before, SessionStore.sessionEpoch)
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
        const val POLL_MS = 5L
        const val SLOW_LOAD_MS = 150L

        /**
         * 검증이 늦게 답하는 시간. 순서가 뒤집혔다면 `restored` 가 이 시간만큼 먼저
         * 올라오므로, 기다리지 않고 확인하는 테스트가 그 틈을 잡는다.
         */
    }

    @Test
    fun `복원한 세션이 죽었으면 로그아웃한다`() {
        // 다른 기기에서 탈퇴했거나 비밀번호를 바꿨으면 디스크의 토큰은 이미 죽은 것이다
        persistence.stored = PersistedSession(tokens, profile)
        validator.result = SessionValidation.Expired

        bind()
        awaitRestored()
        awaitPersisted { !SessionStore.isLoggedIn }

        assertNull(SessionStore.tokens)
    }

    /**
     * A0 시작 게이트의 전제. (이슈 #99 · `screen-api-matrix` A0)
     *
     * `RunningGuApp` 은 `restored` 가 올라온 **그 순간의** `isLoggedIn` 하나로 홈/로그인을
     * 정하고 다시 계산하지 않는다. 그러니 검증이 그 전에 끝나 있어야 한다.
     *
     * **시간이 아니라 게이트로 본다.** 검증기를 늦게 답하게 만드는 방식은 그 지연보다
     * 폴링이 느려지면 흔들린다. 여기서는 검증기를 아예 세워 두고 `restored` 가 아직
     * 올라오지 않았음을 확인한 뒤 풀어 준다 — 기계가 느려도 결과가 같다(#167 리뷰).
     *
     * `restored` 를 검증보다 먼저 올리도록 순서를 바꾸면 이 테스트만 실패한다 — 그 상태가
     * 곧 "죽은 세션으로 홈이 열렸다 튕기는" 화면이다.
     */
    @Test
    fun `검증이 끝나기 전에는 시작 화면을 열지 않는다`() = runBlocking {
        persistence.stored = PersistedSession(tokens, profile)
        val gate = CompletableDeferred<Unit>()
        validator.gate = gate
        validator.result = SessionValidation.Expired

        bind()
        withTimeout(TIMEOUT_MS) { while (validator.calls == 0) delay(POLL_MS) }

        // 검증이 도는 중이다. 이때 시작 화면이 열리면 죽은 세션으로 홈이 뜬다
        assertFalse(
            "검증 중에 시작 화면이 열리면 홈이 번쩍였다 로그인으로 튕긴다",
            SessionStore.restored.value,
        )

        gate.complete(Unit)
        awaitRestored()

        // 열렸을 때는 이미 정리가 끝나 있어야 로그인 화면으로 곧장 간다
        assertFalse(SessionStore.isLoggedIn)
        assertNull(SessionStore.tokens)
    }

    @Test
    fun `살아 있으면 서버 프로필로 갈아끼운다`() {
        // 저장소가 서버라, 다른 기기에서 바꾼 닉네임이 여기서 따라온다 (SPEC §9.3)
        persistence.stored = PersistedSession(tokens, profile.copy(nickname = "옛이름"))
        validator.result = SessionValidation.Valid(profile.copy(nickname = "새이름"))

        bind()
        awaitRestored()
        awaitPersisted { SessionStore.session.value?.nickname == "새이름" }

        assertEquals("새이름", SessionStore.session.value?.nickname)
    }

    @Test
    fun `못 물어봤으면 세션을 지킨다`() {
        // 지하철에서 앱을 켰다고 로그아웃되면 안 된다
        persistence.stored = PersistedSession(tokens, profile)
        validator.result = SessionValidation.Unknown

        bind()
        awaitRestored()

        assertTrue(SessionStore.isLoggedIn)
        assertEquals(tokens, SessionStore.tokens)
    }

    @Test
    fun `게스트로 시작하면 서버에 물어보지 않는다`() {
        // 토큰이 없으면 물어볼 것도 없다. 앱 시작마다 헛 왕복을 만들지 않는다
        bind()
        awaitRestored()

        assertEquals(0, validator.calls)
    }

    @Test
    fun `로그아웃은 디스크에서 지워질 때까지 기다린다`() = runBlocking {
        // 예약만 하고 돌아오면, 화면이 넘어간 직후 프로세스가 죽었을 때 토큰이 되살아난다
        bind()
        awaitRestored()
        SessionStore.signIn(profile, tokens)
        awaitPersisted { persistence.stored != null }

        val cleared = SessionStore.signOutAndAwait()

        // 기다렸으니 돌아온 시점에 이미 없어야 한다
        assertTrue(cleared)
        assertNull(persistence.stored)
        assertTrue(persistence.cleared)
        assertFalse(SessionStore.isLoggedIn)
    }

    @Test
    fun `지우는 중에 scope 가 취소돼도 디스크에서 지워진다`() = runBlocking {
        // 로그아웃 직후 화면이 사라지면 앱 scope 가 끊길 수 있다. 그때 예약해 둔 삭제만
        // 믿으면 토큰이 남는다 — signOutAndAwait 이 자기 호출자 안에서 끝내야 하는 이유다
        bind()
        awaitRestored()
        SessionStore.signIn(profile, tokens)
        awaitPersisted { persistence.stored != null }

        // 저장·복원을 돌리던 scope 를 먼저 끊는다
        scope.cancel()

        SessionStore.signOutAndAwait()

        assertNull(persistence.stored)
        assertTrue(persistence.cleared)
        assertFalse(SessionStore.isLoggedIn)
    }

    @Test
    fun `지우기가 실패하면 로그아웃하지 않고 실패를 알린다`() = runBlocking {
        // 못 지웠는데 로그인 화면으로 보내면, 다음 실행에 이전 계정이 되살아난다 (#89 리뷰).
        // 디스크를 먼저 지우고 메모리를 비우므로, 실패하면 **아무것도 안 바뀐 상태**로 돌아온다
        bind()
        awaitRestored()
        SessionStore.signIn(profile, tokens)
        awaitPersisted { persistence.stored != null }
        persistence.throwOnClear = true

        val cleared = SessionStore.signOutAndAwait()

        assertFalse("실패를 성공으로 돌려줬다", cleared)
        assertTrue("로그인 상태가 유지돼야 다시 시도할 수 있다", SessionStore.isLoggedIn)
        assertEquals(tokens, SessionStore.tokens)
    }

}

/** 디스크 대신 메모리에 담아 두는 가짜 저장소. */
private class RecordingPersistence : SessionPersistence {

    @Volatile var stored: PersistedSession? = null
    @Volatile var cleared = false
    @Volatile var throwOnLoad = false
    @Volatile var throwOnSave = false
    @Volatile var throwOnClear = false
    @Volatile var loadDelayMs = 0L

    override suspend fun load(): PersistedSession? {
        if (loadDelayMs > 0) delay(loadDelayMs)
        if (throwOnLoad) throw IllegalStateException("세션 파일이 깨졌다")
        return stored
    }

    override suspend fun save(session: PersistedSession) {
        if (throwOnSave) throw IllegalStateException("디스크가 가득 찼다")
        stored = session
    }

    override suspend fun clear() {
        if (throwOnClear) throw IllegalStateException("디스크가 읽기 전용이다")
        stored = null
        cleared = true
    }
}


/** 정해 둔 답만 돌려주는 가짜 검증기. */
private class RecordingValidator : SessionValidator {

    @Volatile
    var result: SessionValidation = SessionValidation.Unknown

    @Volatile
    var calls = 0
        private set

    /** 세워 두고 싶을 때 넣는다. 완료시켜야 검증이 끝난다 — 검증과 `restored` 의 순서를 보는 테스트가 쓴다. */
    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun validate(): SessionValidation {
        calls++
        gate?.await()
        return result
    }
}
