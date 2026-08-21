package com.runninggu.app.data.local

import com.runninggu.app.domain.LatLng

/**
 * "내 위치" 한 번 조회. (SPEC §4.11-1 ①)
 *
 * **실패를 종류별로 돌려준다.** 화면이 할 말이 다르기 때문이다 — 권한을 거부한 사람에게
 * "잠시 뒤 다시" 라고 하면 눌러도 계속 실패하고, 위치가 늦게 잡히는 사람에게 "권한을
 * 허용해 주세요" 라고 하면 이미 허용한 걸 또 하라는 말이 된다.
 *
 * 화면은 이 인터페이스만 본다 — 테스트에서 가짜로 갈아끼운다.
 */
interface LocationProvider {

    /**
     * 지금 위치. [TIMEOUT_MS] 안에 못 잡으면 [LocationResult.Timeout] 이다.
     *
     * **어떤 경우에도 예외를 던지지 않는다.** 출발지는 검색·프리셋으로도 정할 수 있어서
     * (NFR-15) 여기서 실패해도 화면은 계속 쓸 수 있어야 한다.
     */
    suspend fun current(): LocationResult

    companion object {
        /** SPEC §4.11-1 ① 이 못 박은 값. 넘기면 검색·프리셋으로 유도한다. */
        const val TIMEOUT_MS = 6_000L
    }
}

sealed interface LocationResult {

    data class Found(val point: LatLng) : LocationResult

    /** 권한이 없다. 다시 눌러도 안 되므로 화면이 검색·프리셋으로 유도한다. */
    data object PermissionDenied : LocationResult

    /** 6초 안에 못 잡았다. 실내·지하에서 흔하다 — 다시 누르면 될 수도 있다. */
    data object Timeout : LocationResult

    /** 위치 서비스가 꺼졌거나 기기가 못 준다. */
    data object Unavailable : LocationResult
}
