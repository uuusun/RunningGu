package com.runninggu.app.data.repository

import com.runninggu.app.data.model.Contest
import com.runninggu.app.data.remote.FavoriteApi
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.mapper.toContest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 찜 API 창구. (API 명세 §7-C · SPEC 결정-16 · AP-21)
 *
 * 찜의 SSOT 는 서버다(§9.3). 앱은 낙관적으로 UI 를 먼저 바꾸고, 실패하면 되돌린다 —
 * 하트는 반응이 즉각적이어야 하고, 목록 재조회를 기다리면 탭이 느리게 느껴진다.
 *
 * [add]·[remove] 는 **멱등**이다(§7-C, 이미 찜이어도 `204`). 다만 멱등성은 *같은* 요청을
 * 두 번 보내도 안전하다는 뜻이지 **도착 순서를 보장하지는 않는다.** `PUT` 과 `DELETE` 는
 * 서로 다른 연산이라 순서가 뒤집히면 서버 최종 상태가 화면과 갈린다. 그래서
 * `FavoriteStore` 가 대회별로 요청을 한 줄로 세운다(#64 리뷰).
 *
 * ## 왜 `Result` 와 예외가 섞여 있나
 *
 * 하트 3종([loadFavoriteIds]·[add]·[remove])은 `Result` 다 — 실패해도 화면을 오류로 덮지
 * 않고 **되돌리기만** 하기 때문에, 호출부가 성공/실패만 알면 된다.
 *
 * [list] 는 다른 저장소들처럼 **던진다.** S10 [찜한 대회] 는 로딩·내용·빈·오류 네 상태를
 * 그려야 해서(§3-5) 호출부가 실패 종류를 봐야 한다.
 */
interface FavoriteRepository {

    /**
     * 찜한 대회 id 전부. 인증이 필요하며 게스트는 부르지 않는다.
     *
     * **화면 키(`Contest.id`) 기준**이다 — 하트는 S2 카드·S3 상세·S10 이 같은 값을 봐야 하는데
     * 그 화면들이 들고 다니는 것이 이 키다.
     */
    suspend fun loadFavoriteIds(): Result<Set<String>>

    /** 찜한 대회 한 장. (`GET /me/favorites` · Pageable §0-4) */
    suspend fun list(page: Int = 0, size: Int = DEFAULT_PAGE_SIZE): FavoritePage

    /** `PUT /me/favorites/{contestId}` — 멱등. */
    suspend fun add(contestId: String): Result<Unit>

    /** `DELETE /me/favorites/{contestId}` — 멱등. */
    suspend fun remove(contestId: String): Result<Unit>

    companion object {
        /** 개인 목록 기본 페이지 크기 🔒(§0-4). */
        const val DEFAULT_PAGE_SIZE = 20

        /** 최대 페이지 크기 (§0-4). id 를 모을 때는 왕복을 줄이려고 이걸 쓴다. */
        const val MAX_PAGE_SIZE = 50
    }
}

/** 찜 목록 한 장. 비활성 대회도 그대로 온다(§7-C 🔒). */
data class FavoritePage(
    val contests: List<Contest>,
    val hasNext: Boolean,
    val totalElements: Long,
)

/**
 * 서버 구현. (API 명세 §7-C)
 *
 * **canonical id 가 없는 대회는 서버에 보내지 않는다.** 번들·오프라인 항목은 크롤 원천
 * 문자열을 id 로 갖는데(`roadrun-41543`), 숫자로 바꿔 보내면 없는 대회를 찜하는 꼴이다.
 * 그런 항목은 실패로 올려 하트를 되돌린다 — 서버에 없는 것을 찜한 척하면 다음 조회에서
 * 조용히 사라진다.
 */
class RemoteFavoriteRepository(private val api: FavoriteApi) : FavoriteRepository {

    /**
     * **끝까지 받아 온다.** 하트는 어느 화면에서나 같아야 하는데 목록이 Pageable 이라,
     * 첫 장만 읽으면 21번째부터 찜한 대회가 빈 하트로 보인다.
     *
     * 한 장에 50건이라 보통 한 번에 끝난다. [MAX_PAGES] 는 서버가 `hasNext` 를 계속
     * 참으로 주는 사고에 대비한 것이지 정상 상한이 아니다.
     *
     * TODO(#105): Room 읽기 캐시가 들어오면 시작할 때마다 전부 받지 않아도 된다.
     */
    override suspend fun loadFavoriteIds(): Result<Set<String>> = runCatching {
        apiCall {
            val ids = mutableSetOf<String>()
            var page = 0
            while (page < MAX_PAGES) {
                val dto = api.list(page = page, size = FavoriteRepository.MAX_PAGE_SIZE)
                dto.content.forEach { ids += it.id.toString() }
                if (!dto.page.hasNext) break
                page++
            }
            ids
        }
    }

    override suspend fun list(page: Int, size: Int): FavoritePage = apiCall {
        val dto = api.list(page = page, size = size)
        FavoritePage(
            contests = dto.content.map { it.toContest() },
            hasNext = dto.page.hasNext,
            totalElements = dto.page.totalElements,
        )
    }

    override suspend fun add(contestId: String): Result<Unit> =
        withServerId(contestId) { apiCall { api.add(it) } }

    override suspend fun remove(contestId: String): Result<Unit> =
        withServerId(contestId) { apiCall { api.remove(it) } }

    /**
     * **취소를 실패로 접지 않는다.** `runCatching` 은 [CancellationException] 까지 잡는데,
     * 쓰기에서 그러면 서버는 이미 반영했는데 앱만 실패로 알고 하트를 되돌린다. 취소는
     * 요청 결과가 아니라 **호출자가 사라졌다는 신호**라 그대로 올려보낸다(#173 리뷰).
     *
     * 이 경로 자체는 `FavoriteStore` 가 앱 수명 스코프에서 돌려 호출자 취소가 닿지
     * 않지만, 여기서도 막아 둔다 — 다음에 다른 자리에서 부를 때 같은 사고가 나지 않게.
     */
    private inline fun withServerId(contestId: String, call: (Long) -> Unit): Result<Unit> {
        val serverId = contestId.toLongOrNull()
            ?: return Result.failure(IllegalArgumentException("canonical id 가 없는 대회: $contestId"))
        return try {
            Result.success(call(serverId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private companion object {
        /** 폭주 방지용 상한. 50건 × 20장 = 1000건이면 정상 사용에서 닿지 않는다. */
        const val MAX_PAGES = 20
    }
}

/**
 * 백엔드 찜 API 가 붙기 전까지 쓰던 스텁. **지금은 테스트에서만 쓴다.**
 *
 * 프로세스 메모리에만 남는다 — 서버 SSOT 를 흉내 내는 자리라 `FavoriteStore` 의 캐시와
 * 일부러 분리해 뒀다. 그래야 낙관적 갱신·롤백 경로가 실제로 동작하는지 확인할 수 있다.
 */
object FakeFavoriteRepository : FavoriteRepository {

    /**
     * 이미 찜해 둔 대회로 시작한다.
     *
     * 지난 대회·비활성 대회는 **공개 목록(S2)에 나오지 않는다**(§3-1 🔒). 찜·저장 동선에서만
     * 만나는 것들이라, 씨앗을 안 두면 화면에서 흐림과 "정보 제공 종료" 를 볼 방법이 없다.
     */
    private val stored = mutableSetOf("chungbuk-past", "jeonbuk-ended")

    override suspend fun loadFavoriteIds(): Result<Set<String>> {
        delay(NETWORK_DELAY_MS)
        return Result.success(stored.toSet())
    }

    /** 스텁에는 카드 데이터가 없다. 목록 화면은 서버 구현에서만 확인한다. */
    override suspend fun list(page: Int, size: Int): FavoritePage =
        FavoritePage(contests = emptyList(), hasNext = false, totalElements = 0)

    override suspend fun add(contestId: String): Result<Unit> {
        delay(NETWORK_DELAY_MS)
        stored += contestId
        return Result.success(Unit)
    }

    override suspend fun remove(contestId: String): Result<Unit> {
        delay(NETWORK_DELAY_MS)
        stored -= contestId
        return Result.success(Unit)
    }

    private const val NETWORK_DELAY_MS = 250L
}
