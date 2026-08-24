package com.runninggu.app.ui.course

import com.runninggu.app.data.model.PoiItem

/**
 * S7 동선 → S8 러닝코스 연계로 넘기는 값. (SPEC §4.10 · §4.11-1 · 매핑표 D-15 개정)
 *
 * 매핑표가 무엇을 넘길지 못 박아 두었다.
 *
 * > S7→S8은 출발지·`min(RECOVERY.walk,5)` 목표거리만 `CourseLaunchContext`로 전달.
 * > **종목·난이도는 전달하지 않고 좌표를 route 문자열에 넣지 않음**
 *
 * **그래서 route 인자가 아니라 여기로 넘긴다.** 좌표를 route 문자열에 넣으면 백스택과
 * 딥링크에 위치가 남는다 — 사용자 좌표는 로그에도 안 남기는 값이다(AGENTS 8장).
 * 화면 사이 한 번 건네는 것이라 [FavoriteStore] 처럼 싱글턴으로 둔다.
 *
 * **한 번만 쓰인다.** [consume] 이 값을 비우므로, 탭바로 S8 을 다시 열면 프리필이
 * 되살아나지 않는다. 사용자가 그 뒤에 출발지를 직접 바꿨다면 그 선택이 이긴다.
 */
object CourseLaunchContext {

    /**
     * @param stay S4 에서 고른 숙소. 숙소 없이 추천받았으면 null 이다(§4.9) —
     *   그때는 출발지를 프리필하지 않고 목표 거리만 넘긴다
     * @param targetKm `min(RECOVERY.walk, 5)` 로 계산된 기본 목표 거리(§5.1 · AGENTS 6장).
     *   **`walk` 는 거리 라벨이 아니라 상한이다** — 원본을 그대로 옮기면 틀리는 자리다
     */
    data class Request(val stay: PoiItem?, val targetKm: Double)

    @Volatile
    private var pending: Request? = null

    /** S7 이 [러닝코스에서 보기] 를 누를 때 채운다. */
    fun set(stay: PoiItem?, targetKm: Double) {
        pending = Request(stay = stay, targetKm = targetKm)
    }

    /** S8 이 열릴 때 한 번 꺼내 간다. 꺼내면 비워진다. */
    fun consume(): Request? {
        val current = pending
        pending = null
        return current
    }

    /** 싱글턴이라 케이스 사이에 상태가 새면 서로를 깨뜨린다. */
    internal fun resetForTest() {
        pending = null
    }
}
