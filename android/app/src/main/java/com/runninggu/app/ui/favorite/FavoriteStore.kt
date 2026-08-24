package com.runninggu.app.ui.favorite

import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.repository.FavoriteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 찜 토글 결과. 화면이 스낵바 문구와 로그인 유도를 가르는 데 쓴다.
 *
 * [LoginRequired] 는 오류가 아니라 정상 분기다 — 게스트는 탐색만 되고 저장은 로그인이
 * 필요하다(SPEC 결정-4 · §4.5 "게스트가 탭하면 로그인 유도").
 */
sealed interface FavoriteToggleResult {
    /** 게스트가 하트를 눌렀다. 화면은 로그인으로 유도하고 **찜을 예약하지 않는다**(D-27). */
    data object LoginRequired : FavoriteToggleResult

    /** 서버 반영 완료. [nowFavorite] 로 "찜했어요" / "찜을 해제했어요" 를 고른다(결정-16). */
    data class Done(val nowFavorite: Boolean) : FavoriteToggleResult

    /** 서버 실패 — UI 는 이미 서버 상태로 되돌아갔다. */
    data object Failed : FavoriteToggleResult
}

/**
 * 찜한 대회 id 보관소. (SPEC §4.5 · 4.6 · 4.13 · 결정-16 · AP-21)
 *
 * 찜 상태는 S2 카드·S3 상세·S10 마이에 **같은 값으로** 보여야 하는데 화면마다 ViewModel 이
 * 따로라 공유할 자리가 필요하다. SSOT 는 서버이고(§9.3) 이 객체는 그 **읽기 캐시**다 —
 * 양방향 병합의 기준이 아니다.
 *
 * 토글은 **낙관적 갱신**이다. 하트를 누르는 즉시 [favoriteIds] 를 바꿔 화면이 반응하고,
 * 서버가 실패하면 되돌린다. 하트는 반응이 즉각적이어야 하는 컨트롤이라 왕복을 기다리지 않는다.
 *
 * 서버 구현을 본다(#163). 화면은 [favoriteIds] 와 [toggle] 만 보므로 교체가 이 파일
 * 안에서 끝났다.
 *
 * TODO(#105): Room 읽기 캐시를 붙여 오프라인에서 마지막 성공 목록을 읽는다 (SPEC §4.13).
 */
object FavoriteStore {

    /**
     * 서버 구현. **`by lazy` 로 미룬다** — 이 객체가 로드되는 것만으로 Retrofit 을 만들면
     * 단위 테스트가 `FavoriteStore` 를 건드릴 수조차 없다([bind] 를 명시 호출로 둔 것과
     * 같은 이유다).
     */
    private val remote: FavoriteRepository by lazy { ServiceLocator.favoriteRepository }

    /** 테스트가 갈아끼운 것. null 이면 [remote] 를 쓴다. */
    private var override: FavoriteRepository? = null

    private val repository: FavoriteRepository get() = override ?: remote

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    /**
     * 서버에 있다고 **확신하는** 찜. 성공한 요청과 조회 결과만 여기에 남는다.
     *
     * 낙관적 갱신 때문에 [favoriteIds] 는 아직 서버가 모르는 값일 수 있다. 실패했을 때
     * 되돌릴 기준이 필요해서 둘을 따로 들고 있는다.
     */
    private val confirmed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 대회별 자물쇠. 같은 대회의 `PUT`·`DELETE` 가 절대 겹치지 않게 한다. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 대회별로 **떠 있는 요청 수**. [refresh] 가 진행 중인 토글을 덮지 않게 하는 데 쓴다.
     *
     * 집합이 아니라 개수인 이유 — 같은 대회에 토글이 둘 대기 중이면 먼저 끝난 쪽이 집합에서
     * id 를 지워 버린다. 그 틈에 조회가 들어오면 **아직 반영 안 된 서버 상태**를 화면에 씌우고,
     * 뒤이은 요청은 성공 경로라 화면을 다시 손대지 않아 그대로 갈린다(#64 리뷰).
     */
    private val inFlight = ConcurrentHashMap<String, Int>()

    /**
     * 세션 세대. [clear] 마다 올라간다.
     *
     * 요청이 떠 있는 동안 로그아웃할 수 있다. 그때 늦게 끝난 응답이 [confirmed] 나 화면을
     * 건드리면 **다음 사용자에게 이전 사용자의 찜이 보인다.** 시작할 때 세대를 적어 두고
     * 반영 직전에 같은지 확인한다(#64 리뷰).
     */
    private val sessionEpoch = AtomicInteger(0)

    /**
     * 쓰기 세대. **서버 상태를 실제로 바꾼 요청**이 끝날 때마다 올라간다.
     *
     * [inFlight] 로는 못 막는 창이 있다 — 조회가 뜬 뒤 쓰기가 **끝나서 pending 에서 빠진
     * 다음** 그 조회 응답이 도착하면, [refresh] 는 대기 중인 게 없다고 보고 방금 성공한
     * 토글을 과거 값으로 덮는다. 조회 시작 시점의 세대를 적어 두고 대조한다(#173 리뷰).
     */
    private val mutationEpoch = AtomicInteger(0)

    /**
     * 쓰기를 돌릴 **앱 수명 스코프**. [bind] 가 채운다.
     *
     * 호출자의 `viewModelScope` 에서 서버 왕복을 돌리면, 하트를 누르고 바로 화면을 뜰 때
     * 요청이 취소된다. **서버는 클라 취소를 모르므로 찜은 이미 반영돼 있는데** 앱만 실패로
     * 알고 [confirmed] 의 이전 값으로 하트를 되돌린다 — 이 객체가 경고하는 바로 그
     * 서버/화면 갈림이 실제 HTTP 경로에 남았다(#173 리뷰).
     *
     * `null` 이면 [bind] 전이다. 단위 테스트가 스코프 없이 부르는 경로라 그 자리에서 돈다.
     */
    private var writeScope: CoroutineScope? = null

    private var sessionJob: Job? = null

    /**
     * 세션을 구독해 스스로 캐시를 맞춘다. 앱 시작 시 한 번 부른다.
     *
     * 화면 ViewModel 의 `viewModelScope` 에서 부르면 **로그인 직후 화면이 전환되며 그
     * ViewModel 이 죽어 조회가 취소된다.** 그러면 찜해 둔 대회의 하트가 빈 채로 남는다.
     * 그래서 앱 수명과 같은 스코프를 밖에서 받는다([com.runninggu.app.RunningGuApplication]).
     *
     * 객체 초기화가 아니라 명시적 호출인 이유는, 클래스가 로드되는 것만으로 메인 디스패처를
     * 잡으면 단위 테스트에서 이 객체를 건드릴 수조차 없기 때문이다.
     */
    fun bind(scope: CoroutineScope) {
        writeScope = scope
        sessionJob?.cancel()
        sessionJob = scope.launch {
            SessionStore.session
                // 같은 사용자의 프로필 변경(닉네임 등)으로 다시 조회하지 않는다.
                .map { it != null }
                .distinctUntilChanged()
                .collect { loggedIn -> if (loggedIn) refresh() else clear() }
        }
    }

    fun isFavorite(raceId: String): Boolean = raceId in _favoriteIds.value

    /**
     * 서버 목록으로 캐시를 채운다. 세션이 생기면 자동으로 불리고, 마이 진입에서도 부른다.
     *
     * 게스트면 캐시를 비우기만 한다 — 이전 사용자의 찜이 남아 보이면 안 된다.
     */
    suspend fun refresh() {
        if (!SessionStore.isLoggedIn) {
            _favoriteIds.value = emptySet()
            return
        }
        val epoch = sessionEpoch.get()
        // **읽기 전에 쓰기 세대를 적어 둔다.** 조회가 도는 사이 토글이 끝나면 이 목록은
        // 그 쓰기 **전**의 상태라, 그대로 씌우면 방금 성공한 토글이 과거 값으로 덮인다.
        //
        // 버리기만 하면 로그인 직후 첫 조회가 토글 한 번에 날아가 하트가 빈 채로 남는다.
        // 그래서 폐기하고 **다시 읽는다**(#173 리뷰).
        repeat(REFRESH_ATTEMPTS) {
            val mutations = mutationEpoch.get()
            val ids = repository.loadFavoriteIds().getOrElse {
                // 실패는 조용히 둔다 — 마지막 성공 목록을 계속 보여주는 편이 낫다
                // (§4.13 오프라인 규칙).
                return
            }
            // 조회가 도는 사이 로그아웃했으면 이전 사용자의 목록이다. 버린다.
            if (epoch != sessionEpoch.get()) return
            if (mutations != mutationEpoch.get()) return@repeat // 쓰기가 끼어들었다. 다시 읽는다
            val pending = inFlight.keys
            _favoriteIds.update { current ->
                // 요청이 떠 있는 대회는 조회 결과보다 화면 값이 최신이다. 조회가 덮으면
                // 방금 누른 하트가 되돌아갔다가 다시 켜지는 것처럼 깜빡인다.
                (ids - pending) + current.filter { it in pending }
            }
            confirmed.removeAll { it !in pending }
            confirmed += ids - pending
            return
        }
    }

    /**
     * 목록 조회로 알게 된 찜을 캐시에 **더한다**. (#173 리뷰 P2)
     *
     * `GET /me/favorites` 가 준 항목은 전부 찜이다(§7-C). 그런데 카드 목록 조회와
     * [refresh] 의 전체 id 조회가 따로 돌아서, **목록은 떴는데 하트가 전부 빈** 순간이
     * 생긴다. id 조회가 뒤쪽 장에서 실패하면 그 상태로 눌러앉는다.
     *
     * **더하기만 하고 빼지 않는다.** 한 장짜리 부분 목록이라 여기 없다는 것이 해제됐다는
     * 뜻이 아니다. 진행 중인 토글([inFlight])도 건드리지 않는다 — 방금 끈 하트를 목록이
     * 다시 켜면 안 된다.
     */
    fun mergeKnownFavorites(raceIds: Collection<String>) {
        if (raceIds.isEmpty() || !SessionStore.isLoggedIn) return
        val epoch = sessionEpoch.get()
        val known = raceIds.toSet() - inFlight.keys
        if (known.isEmpty() || epoch != sessionEpoch.get()) return
        confirmed += known
        _favoriteIds.update { it + known }
    }

    /**
     * 찜 토글. 게스트면 [FavoriteToggleResult.LoginRequired] 를 돌려주고 아무것도 바꾸지 않는다.
     *
     * 화면은 즉시 바꾸고 서버 호출은 **대회별로 한 줄로 세운다**. 연타를 취소로 처리하지
     * 않는 이유가 여기 있다 — 코루틴을 `cancel()` 해도 **이미 서버에 도착한 요청까지
     * 되돌리지는 못한다.** 취소한 `PUT` 이 서버에서 계속 처리되는 동안 새 `DELETE` 가 먼저
     * 끝나면 화면은 해제인데 서버는 찜인 상태로 갈린다(#64 리뷰).
     *
     * 그래서 앞 요청이 **끝난 뒤에** 다음 요청을 보낸다. 왕복이 한 번 더 들지만 서버 최종
     * 상태가 마지막 탭과 항상 같아진다. 하트 자체는 자물쇠를 기다리지 않으므로 체감은 그대로다.
     */
    suspend fun toggle(raceId: String): FavoriteToggleResult {
        if (!SessionStore.isLoggedIn) return FavoriteToggleResult.LoginRequired

        val epoch = sessionEpoch.get()
        val nowFavorite = raceId !in _favoriteIds.value
        // 화면 반영과 [inFlight] 등록은 **호출자 자리에서 동기로** 끝낸다. 연타의 두 번째
        // 탭이 첫 번째가 정한 방향을 보고 자기 방향을 정해야 하기 때문이다(#64 리뷰).
        applyLocally(raceId, nowFavorite)
        // 같은 대회에 대기 중인 토글이 남아 있으면 id 가 유지된다.
        inFlight.merge(raceId, 1, Int::plus)

        // 서버 왕복만 [writeScope] 로 넘긴다. 호출자가 취소되면 아래 `await` 만 끊기고
        // **쓰기는 끝까지 간다** — 서버에 반영된 것을 앱이 실패로 오해하지 않는다(#173 리뷰).
        val scope = writeScope
        if (scope == null) {
            writeThrough(raceId, nowFavorite, epoch)
        } else {
            scope.async { writeThrough(raceId, nowFavorite, epoch) }.await()
        }

        // 세션이 바뀌었으면 이번 토글은 없던 일이다. LoginRequired 를 주면 방금 로그아웃한
        // 사용자를 로그인 화면으로 떠민다 — 그건 아니다.
        if (epoch != sessionEpoch.get()) return FavoriteToggleResult.Failed

        // 서버가 내 의도대로 됐는지로만 판단한다. 뒤이은 토글이 상태를 또 바꿨더라도
        // 그 호출자가 자기 결과를 따로 알린다.
        return if ((raceId in confirmed) == nowFavorite) {
            FavoriteToggleResult.Done(nowFavorite)
        } else {
            FavoriteToggleResult.Failed
        }
    }

    /**
     * 서버에 실제로 쓰는 부분. **[writeScope] 에서 돈다 — 호출자 취소가 닿지 않는다.**
     *
     * 화면 반영은 이미 [toggle] 이 끝냈다. 여기서는 서버를 대회별로 한 줄로 세우고,
     * 대기 중인 요청이 다 빠지면 화면을 서버 상태에 맞춘다.
     */
    private suspend fun writeThrough(raceId: String, nowFavorite: Boolean, epoch: Int) {
        try {
            locks.computeIfAbsent(raceId) { Mutex() }.withLock {
                // 자물쇠를 기다리는 사이 로그아웃했으면 이전 사용자의 찜을 서버에 쓰지 않는다.
                if (epoch != sessionEpoch.get()) return@withLock
                val serverHas = raceId in confirmed
                // 앞선 토글이 이미 서버를 이 상태로 만들어 놨으면 부를 필요가 없다.
                if (serverHas != nowFavorite) {
                    val outcome =
                        if (nowFavorite) repository.add(raceId) else repository.remove(raceId)
                    // 요청이 도는 사이 로그아웃했으면 이번 세션에 반영하지 않는다.
                    if (outcome.isSuccess && epoch == sessionEpoch.get()) {
                        if (nowFavorite) confirmed += raceId else confirmed -= raceId
                        // 이 쓰기 전에 뜬 조회가 화면을 과거 값으로 덮지 못하게 한다(#173 리뷰).
                        mutationEpoch.incrementAndGet()
                    }
                }
            }
        } finally {
            // 마지막 하나가 끝날 때만 지운다. `null` 이면 내가 마지막이었다는 뜻이다.
            val remaining = inFlight.computeIfPresent(raceId) { _, n -> if (n <= 1) null else n - 1 }
            if (remaining == null && epoch == sessionEpoch.get()) {
                // 대기 중인 토글이 더 없으니 화면을 서버 상태에 맞춘다.
                //
                // 실패한 요청을 그 자리에서 되돌리지 않는 이유가 여기 있다 — 뒤에 더 누른
                // 게 있으면 **롤백이 최신 의도를 덮는다.** 찜→해제→찜 에서 첫 요청이
                // 실패하면 마지막 '찜' 이 미찜으로 덮이고, 뒤이은 성공은 화면을 안 건드려
                // 서버는 찜인데 화면은 미찜으로 갈렸다(#64 리뷰).
                //
                // 되돌리기·따라가기를 한 자리에 모으면 규칙이 하나가 된다.
                // **대기 중인 요청이 없을 때 화면은 언제나 서버와 같다.**
                applyLocally(raceId, raceId in confirmed)
            }
        }
    }

    /**
     * 로그아웃·탈퇴 시 캐시를 비운다. 다음 사용자에게 이전 찜이 보이면 계정 사고다.
     *
     * 세대를 올려 **진행 중인 요청까지 무효로 만든다.** 요청 자체는 끝까지 가지만(서버는
     * 클라 취소를 모른다) 그 결과가 이번 세션의 화면·캐시에 닿지 못한다.
     */
    fun clear() {
        sessionEpoch.incrementAndGet()
        _favoriteIds.value = emptySet()
        confirmed.clear()
    }

    /** [refresh] 재시도 횟수. 쓰기가 끼어들면 한 번 더 읽고, 그래도 겹치면 다음 조회에 맡긴다. */
    private const val REFRESH_ATTEMPTS = 2

    private fun applyLocally(raceId: String, favorite: Boolean) {
        _favoriteIds.update { current ->
            if (favorite) current + raceId else current - raceId
        }
    }

    /**
     * 테스트 전용. 스텁 저장소를 갈아끼우고 캐시를 비운다.
     *
     * [writeScope] 를 받으면 쓰기가 그 스코프에서 돈다 — 호출자 취소가 쓰기를 끊지 않는
     * 것을 확인하는 테스트가 이걸 쓴다. 넘기지 않으면 그 자리에서 돈다(기존 테스트 경로).
     */
    internal fun resetForTest(repository: FavoriteRepository, writeScope: CoroutineScope? = null) {
        sessionJob?.cancel()
        sessionJob = null
        this.writeScope = writeScope
        this.override = repository
        sessionEpoch.incrementAndGet()
        _favoriteIds.value = emptySet()
        confirmed.clear()
        locks.clear()
        inFlight.clear()
    }
}
