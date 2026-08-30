package com.runninggu.app.ui.auth

/**
 * 카카오 로그인 SDK 를 쓸 수 있는가. (SPEC §4.1 · §1-7 · NFR-1·3)
 *
 * `KakaoSdk.init()` 이 안 된 채로 로그인을 부르면 SDK 가 예외를 던진다. 그걸 잡아서
 * "로그인 실패" 로 보여 주면 **사용자가 자기 카카오 계정 문제로 오해한다** — 실제로는
 * 앱에 키가 없는 것이다.
 *
 * 초기화를 실제로 해 본 쪽([com.runninggu.app.RunningGuApplication])이 결과를 여기 적어
 * 두고, A1 은 버튼을 그리기 전에 확인한다. [MapAvailability][com.runninggu.app.ui.map.MapAvailability]
 * 와 같은 방식이고 같은 이유다.
 *
 * **키가 없는 것은 드문 일이 아니다.** `local.properties` 는 gitignore 대상이라 CI 에는
 * 아예 없고, 키를 아직 못 받은 팀원도 있다(AGENTS 8장). 그때도 **이메일 로그인은 그대로
 * 동작해야 한다.**
 */
object KakaoAuthAvailability {

    @Volatile
    private var initialized = false

    /** 카카오 로그인을 시도해도 되는가. */
    val isReady: Boolean get() = initialized

    /** `KakaoSdk.init()` 이 성공했을 때만 부른다. */
    fun markReady() {
        initialized = true
    }

    /** 테스트에서 초기 상태로 되돌린다. */
    internal fun resetForTest() {
        initialized = false
    }
}
