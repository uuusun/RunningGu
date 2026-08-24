package com.runninggu.app.ui.favorite

import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.repository.FakeFavoriteRepository
import com.runninggu.app.data.repository.FavoritePage
import com.runninggu.app.data.repository.FavoriteRepository
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
    fun `계정을 갈아타면 이전 계정의 진행 중 요청이 새 조회를 가리지 않는다`() = runBlocking {
        // A 의 토글이 떠 있는 채로 로그아웃 → B 로 로그인 → refresh.
        //
        // inFlight 를 대회 id 만으로 세면 **B 의 조회가 그 대회를 "진행 중" 으로 보고
        // 빼 버린다.** A 의 요청은 이전 세대라 끝나도 화면을 안 고치므로, B 가 그 대회를
        // 찜해 뒀는데도 하트가 꺼진 채 남는다(#173 리뷰).
        val gate = repository.gate("add")
        val aToggle = async { FavoriteStore.toggle(RACE) }
        yield()

        SessionStore.signOut()
        // 실제로는 `bind` 의 세션 구독이 이걸 부른다. 테스트는 구독을 끊어 두므로 직접 부른다.
        FavoriteStore.clear()
        SessionStore.signIn(
            SessionProfile(
                nickname = "다른사람",
                email = "other@example.com",
                loginProvider = LoginProvider.EMAIL,
            ),
        )

        // B 서버에는 같은 대회가 찜으로 있다
        repository.stored += RACE
        FavoriteStore.refresh()

        assertTrue(FavoriteStore.isFavorite(RACE))

        gate.complete(Unit)
        aToggle.await()
        // A 의 요청이 늦게 끝나도 B 의 화면을 되돌리지 않는다
        assertTrue(FavoriteStore.isFavorite(RACE))
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
        val addGate = repository.gate("add")

        val toggling = async { FavoriteStore.toggle(RACE) }
        yield() // 요청이 서버에 닿은 상태로 만든다
        FavoriteStore.clear() // 로그아웃·탈퇴가 부르는 것

        // 로그아웃 뒤에 서버가 응답한다 — 늦게 끝난 요청이 이번 세션을 건드리면 안 된다.
        addGate.complete(Unit)
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
        // PUT 을 붙잡아 두면 "요청이 떠 있는 동안" 이 시간이 아니라 상태로 정해진다.
        val addGate = repository.gate("add")

        val toggling = async { FavoriteStore.toggle(RACE) }
        yield() // 토글이 요청을 보내는 데까지 가게 한다
        FavoriteStore.refresh()
        assertTrue("조회 결과가 진행 중인 토글을 덮었다", RACE in FavoriteStore.favoriteIds.value)

        addGate.complete(Unit)
        toggling.await()
        assertTrue(RACE in FavoriteStore.favoriteIds.value)
    }

    @Test
    fun `앞 요청이 끝나도 뒤 요청이 남아 있으면 조회가 덮지 않는다`() = runBlocking {
        // 연타 → 첫 요청(PUT) 완료 → 두 번째(DELETE) 진행 중에 조회. (#64 리뷰)
        //
        // 이때 서버 목록에는 PUT 만 반영돼 있어 '찜' 으로 온다. 그걸 화면에 씌우면
        // DELETE 가 성공해도 성공 경로는 화면을 다시 손대지 않아 그대로 갈린다.
        // DELETE 를 붙잡아 둔다. 그러면 "PUT 은 끝났고 DELETE 는 아직" 이 시간이 아니라
        // 상태로 정해진다 — 머신이 바빠도 전제가 안 깨진다.
        val removeGate = repository.gate("remove")

        val first = async { FavoriteStore.toggle(RACE) }
        val second = async { FavoriteStore.toggle(RACE) }

        // 첫 요청이 끝날 때까지 기다린다. 이 시점에 두 번째는 이미 inFlight 에 들어가
        // 있고(토글 시작부가 동기라서) DELETE 는 게이트에 걸려 있다.
        first.await()
        assertTrue("PUT 이 반영되지 않았다 — 전제가 깨졌다", RACE in repository.stored)

        FavoriteStore.refresh()
        assertFalse(
            "먼저 끝난 요청이 id 를 지워 조회가 진행 중인 해제를 덮었다",
            RACE in FavoriteStore.favoriteIds.value,
        )

        removeGate.complete(Unit)
        second.await()
        assertFalse(RACE in FavoriteStore.favoriteIds.value)
        assertFalse(RACE in repository.stored)
    }

    @Test
    fun `서버에 반영된 뒤 호출자가 취소돼도 하트가 되돌아가지 않는다`() = runBlocking {
        // 하트를 누르고 **바로 화면을 뜬** 상황이다. `viewModelScope` 가 취소돼도 서버는
        // 이미 찜을 반영했는데, 쓰기를 호출자 스코프에서 돌리면 그 취소가 실패로 접혀
        // [FavoriteStore] 가 이전 상태로 하트를 되돌린다(#173 리뷰 P1).
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        FavoriteStore.resetForTest(repository, writeScope)

        val applied = repository.appliedSignal("add")
        val response = repository.responseGate("add")

        val caller = async { FavoriteStore.toggle(RACE) }
        applied.await()          // 서버가 찜을 반영했다
        caller.cancel()          // 화면을 떠났다 — 호출자만 죽는다
        response.complete(Unit)  // 응답은 그 뒤에 도착한다
        writeScope.coroutineContext.job.children.forEach { it.join() }
        caller.join()

        assertTrue("전제가 깨졌다 — 서버에 반영되지 않았다", RACE in repository.stored)
        assertTrue(
            "서버는 찜인데 화면이 미찜으로 되돌아갔다",
            RACE in FavoriteStore.favoriteIds.value,
        )
        writeScope.cancel()
    }

    @Test
    fun `쓰기가 끝난 뒤 도착한 조회는 화면을 덮지 않는다`() = runBlocking {
        // GET 시작 → 토글 완료 → GET 응답. [inFlight] 로는 못 막는 창이다 — 쓰기가 이미
        // 끝나서 pending 에서 빠진 뒤에 낡은 목록이 도착한다(#173 리뷰 P1).
        val read = repository.listReadSignal()   // 조회가 (아직 빈) 목록을 읽은 순간
        val response = repository.listGate()     // 그 조회의 응답을 붙잡아 둔다

        val refreshing = async { FavoriteStore.refresh() }
        read.await()

        FavoriteStore.toggle(RACE)               // 토글이 끝까지 간다 — pending 에서도 빠진다
        assertTrue("전제가 깨졌다 — 토글이 서버에 반영되지 않았다", RACE in repository.stored)
        assertTrue(RACE in FavoriteStore.favoriteIds.value)

        response.complete(Unit)                  // 이제 쓰기 전 목록이 도착한다
        refreshing.await()

        assertTrue(
            "쓰기 전에 뜬 조회가 방금 성공한 토글을 과거 값으로 덮었다",
            RACE in FavoriteStore.favoriteIds.value,
        )
    }

    @Test
    fun `목록 조회로 알게 된 찜은 하트에 더해진다`() = runBlocking {
        // 목록 GET 은 성공했는데 전체 id 조회가 늦거나 실패한 창. 목록에 있다는 것 자체가
        // 찜이라는 뜻이므로(§7-C) 빈 하트로 두지 않는다(#173 리뷰 P2).
        FavoriteStore.mergeKnownFavorites(listOf(RACE))

        assertTrue(RACE in FavoriteStore.favoriteIds.value)
        // 서버가 안다고 확신하는 값이라 되돌림 기준에도 들어간다 — 다시 눌러 끌 수 있다.
        assertEquals(FavoriteToggleResult.Done(false), FavoriteStore.toggle(RACE))
        assertFalse(RACE in repository.stored)
    }

    @Test
    fun `목록 병합이 진행 중인 해제를 되살리지 않는다`() = runBlocking {
        // 하트를 꺼서 DELETE 가 떠 있는데 목록 조회가 돌아왔다. 그 목록에는 아직 이 대회가
        // 들어 있다 — 그걸 병합하면 방금 끈 하트가 다시 켜진다.
        FavoriteStore.mergeKnownFavorites(listOf(RACE))
        val removeGate = repository.gate("remove")

        val toggling = async { FavoriteStore.toggle(RACE) }
        yield() // 해제 요청이 서버에 닿은 상태로 만든다
        FavoriteStore.mergeKnownFavorites(listOf(RACE))

        assertFalse("목록 병합이 진행 중인 해제를 덮었다", RACE in FavoriteStore.favoriteIds.value)

        removeGate.complete(Unit)
        toggling.await()
        assertFalse(RACE in FavoriteStore.favoriteIds.value)
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

    /**
     * 이 연산을 붙잡아 둔다. 테스트가 완료시킬 때까지 서버가 응답하지 않는 상태다.
     *
     * **`delay` 로 "그쯤이면 끝났겠지" 를 하지 않기 위해서다.** 실측 시간에 기대면 머신이
     * 바쁠 때 전제가 깨져서 테스트가 간헐 실패한다(실제로 하루에 세 번 깨졌다). 게이트는
     * "언제" 가 아니라 "무엇이 끝났는가" 로 순서를 정한다.
     */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** 그 연산이 서버에 반영된 순간 완료된다. 테스트가 이걸 기다린다. */
    val applied = mutableMapOf<String, CompletableDeferred<Unit>>()

    /**
     * **반영을 끝낸 뒤 응답만** 늦추는 게이트. [gates] 와 다른 자리다.
     *
     * [gates] 는 서버가 아직 처리하지 않은 상태를, 이건 **처리는 끝났는데 응답이 아직인**
     * 상태를 만든다. 「서버 반영 후 호출자 취소」가 이 창에서만 재현된다(#173 리뷰).
     */
    val responseGates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** 조회 응답을 순서대로 붙잡아 둔다. `refresh` 가 다시 읽을 수 있어서 하나가 아니라 큐다. */
    val listGates: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque()

    /** 조회가 서버 목록을 **읽은** 순간 완료된다. */
    val listReads: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque()

    private var running = 0

    fun gate(op: String): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { gates[op] = it }

    fun appliedSignal(op: String): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { applied[op] = it }

    fun responseGate(op: String): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { responseGates[op] = it }

    fun listGate(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { listGates.addLast(it) }

    fun listReadSignal(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { listReads.addLast(it) }

    /** 이 파일은 목록 화면을 보지 않는다 — 하트만 본다. */
    override suspend fun list(page: Int, size: Int): FavoritePage =
        FavoritePage(contests = emptyList(), hasNext = false, totalElements = 0)

    override suspend fun loadFavoriteIds(): Result<Set<String>> {
        // **읽는 시점을 먼저 고정한다.** 뒤에서 붙잡아 두면 "목록은 읽었는데 응답은 아직"
        // 창이 시간이 아니라 상태로 정해진다.
        val snapshot = stored.toSet()
        listReads.removeFirstOrNull()?.complete(Unit)
        listGates.removeFirstOrNull()?.await()
        return Result.success(snapshot)
    }

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
            // 게이트가 걸려 있으면 테스트가 풀어 줄 때까지 서버가 응답하지 않는 것으로 본다.
            gates[op]?.await()
            withContext(NonCancellable) {
                delay(wait)
                apply()
                // **서버가 반영을 끝낸 시점.** 여기까지는 클라 취소와 무관하게 일어난다.
                applied[op]?.complete(Unit)
            }
            // **응답 수신은 취소된다.** 호출자가 이미 사라졌으면 여기서 깨진다 — 실제
            // Retrofit `suspend` 호출과 같은 조건이고, 「서버는 반영했는데 앱은 실패로
            // 안다」가 정확히 이 창에서 생긴다(#173 리뷰).
            responseGates[op]?.await()
            return Result.success(Unit)
        } finally {
            running--
        }
    }
}
