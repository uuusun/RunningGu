package com.runninggu.app.ui.map

/**
 * 카카오맵 SDK 를 쓸 수 있는가. (SPEC §3-8 · NFR-1·3)
 *
 * **`MapView` 는 초기화 실패를 알려 주지 않는다.** `KakaoMapSdk.init()` 이 안 된 채로
 * `start()` 를 부르면 SDK 가 자기 로그에만 남기고 [com.kakao.vectormap.MapLifeCycleCallback]
 * 의 `onMapError` 는 부르지 않는다.
 *
 * ```
 * E K3fAApi : MapView Start failed. KakaoMapSdk.init() must be called first.
 * ```
 *
 * 그래서 [RunningGuMap] 이 실패를 못 알아채고 **빈 회색 판**만 남긴다 — "실패하면 지도
 * 자리에만 안내 문구를 띄운다" 는 §3-8 약속이 이 경로에서만 안 지켜졌다(#162).
 *
 * 초기화를 실제로 해 본 쪽(`RunningGuApplication`)이 결과를 여기 적어 두고, 지도는
 * 그리기 전에 확인한다.
 *
 * **초기화가 실패하는 건 드문 일이 아니다.** x86_64 에뮬레이터에는 카카오맵이 그 ABI 용
 * 네이티브 라이브러리를 주지 않아(arm64-v8a · armeabi-v7a 만 있다) 항상 실패한다.
 * 키를 못 받은 팀원과 CI 도 마찬가지다.
 */
object MapAvailability {

    @Volatile
    private var initialized = false

    /** 지도를 그려도 되는가. */
    val isReady: Boolean get() = initialized

    /** `KakaoMapSdk.init()` 이 성공했을 때만 부른다. */
    fun markReady() {
        initialized = true
    }

    /** 테스트에서 초기 상태로 되돌린다. */
    fun resetForTest() {
        initialized = false
    }
}
