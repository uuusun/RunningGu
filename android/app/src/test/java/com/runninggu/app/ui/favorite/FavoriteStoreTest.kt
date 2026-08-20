package com.runninggu.app.ui.favorite

import com.runninggu.app.ui.auth.LoginProvider
import com.runninggu.app.ui.auth.SessionProfile
import com.runninggu.app.ui.auth.SessionStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 찜 캐시의 연타·실패 동작. (AP-21 · #64 리뷰)
 *
 * 핵심은 **코루틴 취소가 서버 처리를 되돌려 주지 않는다**는 것이다. 예전 구현은 새 토글이
 * 오면 이전 `Job` 을 `cancel()` 했는데, 이미 서버에 도착한 `PUT` 은 그대로 처리된다.
 * 그 `PUT` 이 늦게 끝나면 화면은 해제인데 서버는 찜인 상태로 갈린다. 그래서 여기 스텁은
 * **클라이언트 취소를 무시하고** 요청을 끝까지 처리한다 — 실제 서버와 같은 조건이다.
 */
class FavoriteStoreTest {

    private lateinit var repository: RecordingFavoriteRepository

    @Before
    fun signIn() {
        repository = RecordingFavoriteRepository()
        FavoriteStore.resetForTest(repository)
        SessionStore.signIn(
            SessionProfile(
                nickname = "테스터",
                email = "tester@example.com",
                loginProvider = LoginProvider.EMAIL,
            ),
        )
    }

    @After
    fun signOut() {
        SessionStore.signOut()
        FavoriteStore.resetForTest(FakeFavoriteRepository)
    }

    @Test
    fun `연타해도 같은 대회 요청이 겹치지 않는다`() = runBlocking {
        // 첫 요청(PUT)이 느리고 두 번째(DELETE)가 빠른, 순서가 뒤집히기 딱 좋은 조건.
        repository.delaysMs = ArrayDeque(listOf(300L, 10L))

        val first = async { FavoriteStore.toggle(RACE) }
        val second = async { FavoriteStore.toggle(RACE) }
        first.await()
        second.await()

        assertEquals(listOf("add:$RACE", "remove:$RACE"), repository.calls)
        // 겹쳐서 나갔다면 도착 순서를 서버가 정하게 된다 — 그걸 막는 게 이 PR 의 요지다.
        assertEquals(1, repository.maxConcurrent)
    }

    @Test
    fun `연타 후 서버 상태가 마지막 탭과 같다`() = runBlocking {
        repository.delaysMs = ArrayDeque(listOf(300L, 10L))

        val first = async { FavoriteStore.toggle(RACE) }
        val second = async { FavoriteStore.toggle(RACE) }
        first.await()
        second.await()

        // 마지막 탭은 '해제' 였다. 화면과 서버가 모두 해제여야 한다.
        assertFalse(RACE in FavoriteStore.favoriteIds.value)
        assertFalse(RACE in repository.stored)
    }

    @Test
    fun `세 번 눌러도 마지막 의도만 남는다`() = runBlocking {
        repository.delaysMs = ArrayDeque(listOf(200L, 10L, 10L))

        val jobs = List(3) { async { FavoriteStore.toggle(RACE) } }
        jobs.forEach { it.await() }

        // 찜 → 해제 → 찜. 홀수 번이라 최종은 찜이다.
        assertTrue(RACE in FavoriteStore.favoriteIds.value)
        assertTrue(RACE in repository.stored)
        assertEquals(1, repository.maxConcurrent)
    }

    @Test
    fun `첫 요청이 실패해도 마지막 의도가 화면에 남는다`() = runBlocking {
        // 찜 → 해제 → 찜 을 연타하고 첫 PUT 이 실패한다. (#64 리뷰)
        //
        // 실패를 그 자리에서 되돌리면 롤백이 마지막 '찜' 을 미찜으로 덮는다. 뒤이은 PUT 은
        // 성공하지만 성공 경로는 화면을 손대지 않아, 서버는 찜인데 화면은 미찜으로 갈린다.
        repository.failNext = true
        repository.delaysMs = ArrayDeque(listOf(50L, 10L, 10L))

        val jobs = List(3) { async { FavoriteStore.toggle(RACE) } }
        jobs.forEach { it.await() }

        assertTrue("서버는 찜인데 화면이 따라오지 않았다", RACE in FavoriteStore.favoriteIds.value)
        assertTrue(RACE in repository.stored)
    }

    @Test
    fun `로그아웃하면 늦게 끝난 요청이 이전 찜을 되살리지 않는다`() = runBlocking {
        // 요청이 떠 있는 동안 로그아웃한다. 서버는 클라 취소를 모르니 저장은 끝까지 된다 —
        // 그 결과가 다음 사용자 화면에 닿으면 계정 사고다. (#64 리뷰)
        repository.delaysMs = ArrayDeque(listOf(300L))

        val toggling = async { FavoriteStore.toggle(RACE) }
        delay(50)
        FavoriteStore.clear() // 로그아웃·탈퇴가 부르는 것

        toggling.await()
        assertFalse(
            "로그아웃 뒤에 이전 세션의 찜이 화면에 되살아났다",
            RACE in FavoriteStore.favoriteIds.value,
        )
    }

    @Test
    fun `실패하면 서버 상태로 되돌리고 Failed 를 준다`() = runBlocking {
        repository.failNext = true

        val result = FavoriteStore.toggle(RACE)

        assertEquals(FavoriteToggleResult.Failed, result)
        assertFalse(RACE in FavoriteStore.favoriteIds.value)
        assertFalse(RACE in repository.stored)
    }

    @Test
    fun `게스트는 서버를 부르지 않는다`() = runBlocking {
        SessionStore.signOut()

        val result = FavoriteStore.toggle(RACE)

        assertEquals(FavoriteToggleResult.LoginRequired, result)
        assertTrue(repository.calls.isEmpty())
        assertFalse(RACE in FavoriteStore.favoriteIds.value)
    }

    @Test
    fun `조회가 진행 중인 토글을 덮지 않는다`() = runBlocking {
        // 서버에는 아직 찜이 없다. 그 목록이 방금 누른 하트를 지우면 안 된다.
        repository.delaysMs = ArrayDeque(listOf(300L))

        val toggling = async { FavoriteStore.toggle(RACE) }
        delay(50) // 요청이 떠 있는 동안
        FavoriteStore.refresh()
        assertTrue("조회 결과가 진행 중인 토글을 덮었다", RACE in FavoriteStore.favoriteIds.value)

        toggling.await()
        assertTrue(RACE in FavoriteStore.favoriteIds.value)
    }

    @Test
    fun `앞 요청이 끝나도 뒤 요청이 남아 있으면 조회가 덮지 않는다`() = runBlocking {
        // 연타 → 첫 요청(PUT) 완료 → 두 번째(DELETE) 진행 중에 조회. (#64 리뷰)
        //
        // 이때 서버 목록에는 PUT 만 반영돼 있어 '찜' 으로 온다. 그걸 화면에 씌우면
        // DELETE 가 성공해도 성공 경로는 화면을 다시 손대지 않아 그대로 갈린다.
        repository.delaysMs = ArrayDeque(listOf(50L, 300L))

        val first = async { FavoriteStore.toggle(RACE) }
        val second = async { FavoriteStore.toggle(RACE) }

        delay(150) // PUT 은 끝났고 DELETE 는 아직인 시점
        assertTrue("PUT 이 아직 안 끝났다 — 타이밍 전제가 깨졌다", RACE in repository.stored)
        FavoriteStore.refresh()
        assertFalse(
            "먼저 끝난 요청이 id 를 지워 조회가 진행 중인 해제를 덮었다",
            RACE in FavoriteStore.favoriteIds.value,
        )

        first.await()
        second.await()
        assertFalse(RACE in FavoriteStore.favoriteIds.value)
        assertFalse(RACE in repository.stored)
    }

    private companion object {
        const val RACE = "roadrun-41543"
    }
}

/**
 * 호출 순서·동시성을 기록하는 스텁.
 *
 * 본문을 [NonCancellable] 로 감싼 것이 핵심이다 — **서버는 클라이언트가 코루틴을 취소한
 * 것을 모른다.** 취소해도 저장은 끝까지 일어나는 실제 조건을 그대로 만든다.
 */
private class RecordingFavoriteRepository : FavoriteRepository {

    val stored = mutableSetOf<String>()
    val calls = mutableListOf<String>()
    var maxConcurrent = 0
    var failNext = false
    var delaysMs: ArrayDeque<Long> = ArrayDeque()

    private var running = 0

    override suspend fun loadFavoriteIds(): Result<Set<String>> = Result.success(stored.toSet())

    override suspend fun add(contestId: String): Result<Unit> =
        call("add", contestId) { stored += contestId }

    override suspend fun remove(contestId: String): Result<Unit> =
        call("remove", contestId) { stored -= contestId }

    private suspend fun call(op: String, id: String, apply: () -> Unit): Result<Unit> {
        calls += "$op:$id"
        running++
        maxConcurrent = maxOf(maxConcurrent, running)
        try {
            val wait = delaysMs.removeFirstOrNull() ?: 0L
            if (failNext) {
                failNext = false
                withContext(NonCancellable) { delay(wait) }
                return Result.failure(IllegalStateException("서버 오류"))
            }
            withContext(NonCancellable) {
                delay(wait)
                apply()
            }
            return Result.success(Unit)
        } finally {
            running--
        }
    }
}
