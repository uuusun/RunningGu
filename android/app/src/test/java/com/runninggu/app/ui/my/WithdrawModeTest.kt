package com.runninggu.app.ui.my

import com.runninggu.app.data.local.LoginProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 탈퇴 재인증을 **무엇으로 할지 정하는 자리.** (§2-2 · #238 리뷰)
 *
 * 처음에는 화면이 이렇게 판정했다.
 *
 * ```kotlin
 * emailAccount = state.profile?.loginProvider == LoginProvider.EMAIL
 * ```
 *
 * **프로필이 null 이면 이 식도 false 다.** 그러면 `emailAccount = false` 이고 화면은
 * 그것을 "카카오" 로 읽어서, EMAIL 계정에 **비밀번호 칸 없이 [탈퇴] 를 열고 카카오
 * SDK 재인증을 시작한다**(@uuusun · #238 리뷰). 다이얼로그가 열린 뒤 세션 수집기가
 * 프로필을 null 로 갱신하면 실제로 난다.
 *
 * 탈퇴는 되돌릴 수 없어서 **모르는 값과 없는 값을 갈라 막아야** 한다. 그래서 판정을
 * 순수 함수로 빼고, 화면 없이 여기서 고정한다.
 */
class WithdrawModeTest {

    @Test
    fun `EMAIL 은 비밀번호로 재인증한다`() {
        assertEquals(WithdrawMode.PASSWORD, withdrawMode(LoginProvider.EMAIL))
    }

    @Test
    fun `KAKAO 는 SDK 토큰으로 재인증한다`() {
        assertEquals(WithdrawMode.KAKAO, withdrawMode(LoginProvider.KAKAO))
    }

    @Test
    fun `프로필이 없으면 막는다`() {
        // **여기가 이 파일의 이유다.** null 이 카카오로 새면 EMAIL 계정이 비밀번호
        // 없이 카카오 재인증을 시작한다 — 짐작으로 진행하지 않는다
        assertEquals(WithdrawMode.BLOCKED, withdrawMode(null))
    }

    @Test
    fun `가입 경로마다 답이 하나씩이고 서로 다르다`() {
        // 갈래가 늘었을 때 둘이 같은 답으로 뭉개지는 것을 막는다
        val answers = LoginProvider.entries.map { withdrawMode(it) }
        assertEquals("가입 경로가 같은 모드로 뭉쳐졌다", answers.size, answers.toSet().size)
        assertEquals(
            "새 가입 경로가 생겼는데 판정이 없다",
            emptyList<WithdrawMode>(),
            answers.filter { it == WithdrawMode.BLOCKED },
        )
    }
}
