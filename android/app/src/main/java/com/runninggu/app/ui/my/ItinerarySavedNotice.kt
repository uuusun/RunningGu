package com.runninggu.app.ui.my

import androidx.lifecycle.SavedStateHandle

/**
 * S7 저장 → 마이[동선]으로 넘기는 안내 문구. (SPEC §4.10 · API 명세 §5-2)
 *
 * §4.10 이 저장 성공을 **"마이[동선] → '마이에 저장했어요'"** 로 못 박아 두었다. 화면을
 * 옮기므로 S7 에는 문구를 그릴 자리가 없고, 옮겨 간 마이가 스낵바로 띄운다.
 *
 * **전역 싱글턴이 아닌 이유는 [com.runninggu.app.ui.course.CourseLaunchContext] 와 같다.**
 * 마이 항목의 [SavedStateHandle] 에 담으면 그 항목보다 오래 살지 못한다. 전역에 두면
 * 저장한 적 없는 다음 진입에 "마이에 저장했어요" 가 뜬다.
 *
 * **다만 이쪽은 읽고 지운다.** 프리필과 달리 안내는 **한 번 보이고 끝나는 것**이라,
 * 남겨 두면 탭을 오갈 때마다 같은 스낵바가 다시 뜬다.
 */
object ItinerarySavedNotice {

    /** 항목의 nav 인자와 섞이지 않게 접두사를 붙인다. */
    private const val KEY_MESSAGE = "itinerarySaved.message"

    /** 저장 직후 쌓인 **마이 항목**의 상태에 담는다. */
    fun set(handle: SavedStateHandle, message: String) {
        handle[KEY_MESSAGE] = message
    }

    /** 한 번만 돌려준다. 없으면 null. */
    fun consume(handle: SavedStateHandle): String? = handle.remove<String>(KEY_MESSAGE)
}
