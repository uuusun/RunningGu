package com.runninggu.app.ui.favorite

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 찜한 대회 id 보관소. (SPEC §4.5 · 4.6 · 4.13 · 결정-16)
 *
 * 찜 상태는 S2 카드·S3 상세·S10 마이에 **같은 값으로** 보여야 하는데, 화면마다
 * ViewModel이 따로라 공유할 자리가 필요하다. 서버가 붙기 전까지 이 객체가 그 자리를 맡는다.
 *
 * TODO(AP-21): `/me/favorites`(API 명세 §7-C) 동기화로 교체한다. 이때
 *  - 프로세스가 죽으면 사라지는 현재 동작 → 서버 SSOT + Room 읽기 캐시
 *  - 게스트가 토글하면 로그인 유도 (SPEC 결정-4)
 * 두 가지를 함께 붙인다. 화면은 [favoriteIds]만 보므로 이 파일 내부만 바뀐다.
 */
object FavoriteStore {

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    fun isFavorite(raceId: String): Boolean = raceId in _favoriteIds.value

    /**
     * 찜 토글. 토글 후 찜 상태이면 true를 돌려준다 — 호출부가 스낵바 문구를 고르는 데 쓴다.
     * (SPEC §3-4: "찜했어요" / "찜을 해제했어요")
     */
    fun toggle(raceId: String): Boolean {
        var nowFavorite = false
        _favoriteIds.update { current ->
            nowFavorite = raceId !in current
            if (nowFavorite) current + raceId else current - raceId
        }
        return nowFavorite
    }
}
