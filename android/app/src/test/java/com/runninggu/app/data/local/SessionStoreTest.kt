package com.runninggu.app.data.local

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
 * 세션 영속. (SPEC §2.2 · AP-14)
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

    @Before
    fun setUp() {
        SessionStore.resetForTest()
        persistence = RecordingPersistence()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

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

        SessionStore.bind(persistence, scope)
        awaitRestored()

        assertTrue(SessionStore.isLoggedIn)
        assertEquals(profile, SessionStore.session.value)
        assertEquals(tokens, SessionStore.tokens)
    }

    @Test
    fun `저장된 게 없으면 게스트로 시작한다`() {
        SessionStore.bind(persistence, scope)
        awaitRestored()

        assertFalse(SessionStore.isLoggedIn)
        assertNull(SessionStore.tokens)
    }

    @Test
    fun `읽다 터져도 시작 화면은 열린다`() {
        // 여기서 restored 가 안 올라가면 **빈 화면에서 영영 못 나온다.**
        // 파일이 깨졌다고 사용자가 할 수 있는 게 재설치뿐이면 안 된다 (NFR-1)
        persistence.throwOnLoad = true

        SessionStore.bind(persistence, scope)
        awaitRestored()

        assertTrue(SessionStore.restored.value)
        assertFalse(SessionStore.isLoggedIn)
    }

    @Test
    fun `저장이 터져도 이번 실행의 로그인은 살아 있다`() {
        persistence.throwOnSave = true
        SessionStore.bind(persistence, scope)
        awaitRestored()

        SessionStore.signIn(profile, tokens)

        assertTrue(SessionStore.isLoggedIn)
        assertEquals(tokens, SessionStore.tokens)
    }

    @Test
    fun `로그인하면 저장한다`() {
        SessionStore.bind(persistence, scope)
        awaitRestored()

        SessionStore.signIn(profile, tokens)

        awaitPersisted { persistence.stored != null }
        assertEquals(PersistedSession(tokens, profile), persistence.stored)
    }

    @Test
    fun `재발급받은 토큰도 저장한다`() {
        // 안 남기면 다음 실행에서 죽은 액세스 토큰으로 시작해 첫 요청이 401 로 떨어진다
        SessionStore.bind(persistence, scope)
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
        SessionStore.bind(persistence, scope)
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

        SessionStore.bind(persistence, scope)
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

        SessionStore.bind(persistence, scope)
        awaitRestored()

        assertEquals(before, SessionStore.sessionEpoch)
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
        const val POLL_MS = 5L
        const val SLOW_LOAD_MS = 150L
    }
}

/** 디스크 대신 메모리에 담아 두는 가짜 저장소. */
private class RecordingPersistence : SessionPersistence {

    @Volatile var stored: PersistedSession? = null
    @Volatile var cleared = false
    @Volatile var throwOnLoad = false
    @Volatile var throwOnSave = false
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
        stored = null
        cleared = true
    }
}
