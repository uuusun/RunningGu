package com.runninggu.app.ui.course

import androidx.lifecycle.SavedStateHandle
import com.runninggu.app.data.model.PoiItem

/**
 * S7 동선 → S8 러닝코스 연계로 넘기는 값. (SPEC §4.10 · §4.11-1 · 매핑표 D-15)
 *
 * 매핑표가 **무엇을 어디에 담아** 넘길지 둘 다 못 박아 두었다.
 *
 * > S7→S8은 출발지·`min(RECOVERY.walk,5)` 목표거리만 `CourseLaunchContext`로 전달.
 * > 종목·난이도는 전달하지 않고 좌표를 route 문자열에 넣지 않음 —
 * > **SavedStateHandle/그래프 상태**로 전달
 *
 * **route 인자가 아닌 이유.** 좌표를 route 문자열에 넣으면 백스택과 딥링크에 사용자 위치가
 * 남는다. 로그에도 안 남기는 값이다(AGENTS 8장).
 *
 * **전역 싱글턴이 아닌 이유.** 값을 S8 백스택 항목의 [SavedStateHandle] 에 담으므로
 * **그 항목보다 오래 살지 못한다.** 프로세스 전역에 두면 연계가 중간에 끊겼을 때 값이 남아,
 * 나중에 탭바로 연 일반 S8 에 이전 숙소 좌표가 주입된다. 반대로 프로세스가 재생성되면
 * 사라진다 — 양쪽 다 이 방식에는 없다(#178 리뷰).
 *
 * **한 진입에 한 번 쓰인다.** [set] 은 방금 쌓인 S8 항목에만 쓰고, [from] 은 그 항목의
 * ViewModel 이 만들어질 때 읽는다. 탭바로 S8 을 다시 열면 **다른 항목**이라 프리필이
 * 되살아나지 않는다 — 그사이 사용자가 출발지를 바꿨다면 그 선택이 이긴다.
 *
 * 값을 읽고 지우지는 않는다. 같은 항목이 프로세스 재생성으로 되살아날 때 프리필도 같이
 * 돌아와야 하기 때문이다. 그 항목이 백스택에서 빠지면 값도 함께 사라진다.
 */
object CourseLaunchContext {

    /** 항목의 nav 인자와 섞이지 않게 접두사를 붙인다. */
    private const val KEY_TARGET_KM = "courseLaunch.targetKm"
    private const val KEY_START_NAME = "courseLaunch.startName"
    private const val KEY_START_LAT = "courseLaunch.startLat"
    private const val KEY_START_LNG = "courseLaunch.startLng"

    /**
     * @param stay S4 에서 고른 숙소. 숙소 없이 추천받았으면 null 이다(§4.9) —
     *   그때는 출발지를 프리필하지 않고 목표 거리만 넘긴다
     * @param targetKm `min(RECOVERY.walk, 5)` 로 계산된 기본 목표 거리(§5.1 · AGENTS 6장).
     *   **`walk` 는 거리 라벨이 아니라 상한이다** — 원본을 그대로 옮기면 틀리는 자리다
     */
    data class Request(val stay: Stay?, val targetKm: Double)

    /**
     * 출발지로 쓸 숙소. [PoiItem] 을 그대로 담지 않는다 —
     * [SavedStateHandle] 은 저장 가능한 값만 받고, S8 이 쓰는 것은 이 셋뿐이다.
     */
    data class Stay(val name: String, val lat: Double, val lng: Double)

    /** S7 이 [러닝코스에서 보기] 로 띄운 **S8 항목**의 상태에 담는다. */
    fun set(handle: SavedStateHandle, stay: PoiItem?, targetKm: Double) {
        handle[KEY_TARGET_KM] = targetKm
        if (stay != null) {
            handle[KEY_START_NAME] = stay.name
            handle[KEY_START_LAT] = stay.lat
            handle[KEY_START_LNG] = stay.lng
        }
    }

    /** 연계로 열린 항목이 아니면 null. 탭바로 그냥 연 S8 이 여기로 온다. */
    fun from(handle: SavedStateHandle): Request? {
        val targetKm = handle.get<Double>(KEY_TARGET_KM) ?: return null
        val name = handle.get<String>(KEY_START_NAME)
        val lat = handle.get<Double>(KEY_START_LAT)
        val lng = handle.get<Double>(KEY_START_LNG)
        val stay = if (name != null && lat != null && lng != null) {
            Stay(name = name, lat = lat, lng = lng)
        } else {
            null
        }
        return Request(stay = stay, targetKm = targetKm)
    }
}
