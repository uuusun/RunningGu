package com.runninggu.app.ui.favorite

import kotlinx.coroutines.delay

/**
 * 찜 API 창구. (API 명세 §7-C · SPEC 결정-16 · AP-21)
 *
 * 찜의 SSOT 는 서버다(§9.3). 앱은 낙관적으로 UI 를 먼저 바꾸고, 실패하면 되돌린다 —
 * 하트는 반응이 즉각적이어야 하고, 목록 재조회를 기다리면 탭이 느리게 느껴진다.
 *
 * [add]·[remove] 는 **멱등**이다(§7-C, 이미 찜이어도 `204`). 다만 멱등성은 *같은* 요청을
 * 두 번 보내도 안전하다는 뜻이지 **도착 순서를 보장하지는 않는다.** `PUT` 과 `DELETE` 는
 * 서로 다른 연산이라 순서가 뒤집히면 서버 최종 상태가 화면과 갈린다. 그래서 [FavoriteStore]
 * 가 대회별로 요청을 한 줄로 세운다(#64 리뷰).
 *
 * TODO(AP-14): `data/remote` 의 Retrofit 구현으로 교체한다. 이때
 *  - [loadFavoriteIds] 는 `GET /me/favorites`(Pageable) 로 바뀌고 카드 데이터까지 함께 온다
 *  - Room 읽기 캐시를 붙여 오프라인에서 마지막 성공 목록을 읽는다 (SPEC §4.13)
 */
interface FavoriteRepository {

    /** `GET /me/favorites` — 찜한 대회 id. 인증 필요하며 게스트는 부르지 않는다. */
    suspend fun loadFavoriteIds(): Result<Set<String>>

    /** `PUT /me/favorites/{contestId}` — 멱등. */
    suspend fun add(contestId: String): Result<Unit>

    /** `DELETE /me/favorites/{contestId}` — 멱등. */
    suspend fun remove(contestId: String): Result<Unit>
}

/**
 * 백엔드 찜 API 가 붙기 전까지 쓰는 스텁.
 *
 * 프로세스 메모리에만 남는다 — 서버 SSOT 를 흉내 내는 자리라 [FavoriteStore] 의 캐시와
 * 일부러 분리해 뒀다. 그래야 낙관적 갱신·롤백 경로가 실제로 동작하는지 확인할 수 있다.
 */
object FakeFavoriteRepository : FavoriteRepository {

    private val stored = mutableSetOf<String>()

    override suspend fun loadFavoriteIds(): Result<Set<String>> {
        delay(NETWORK_DELAY_MS)
        return Result.success(stored.toSet())
    }

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
