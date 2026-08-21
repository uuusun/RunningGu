package com.runninggu.app.ui.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2 입력 검증. (SPEC §4.2 🔒 · 명세 §1-2 · 이슈 #97)
 *
 * 닉네임 길이가 이 파일의 핵심이다. **서버(`NicknamePolicy`)가 코드포인트로 세므로 앱도
 * 같아야 한다.** `String.length`(UTF-16)로 세면 이모지가 2자 이상으로 잡혀서, **같은
 * 닉네임을 앱은 막고 서버는 받는다.** 사용자에게는 "12자 이내인데 왜 안 되지" 로만 보이고
 * 화면에는 이유가 안 나온다.
 */
class AuthValidationTest {

    @Test
    fun `이모지는 코드포인트로 한 자다`() {
        // `🏃` 는 UTF-16 으로 2. length 로 세면 12자 제한에 6개밖에 못 넣는다.
        val twelveRunners = "🏃".repeat(12)

        assertTrue(AuthValidation.isNicknameValid(twelveRunners))
        assertFalse(AuthValidation.isNicknameValid("🏃".repeat(13)))
    }

    @Test
    fun `ZWJ 조합 이모지도 서버와 같은 기준으로 센다`() {
        // `🏃‍♂️` = 달리는사람 + ZWJ + 남성기호 + VS16 → UTF-16 5, 코드포인트 4.
        // 자소 클러스터로는 1 이지만 BreakIterator 가 필요해 P0 에는 과하다 —
        // **서버와 같은 기준으로 맞추는 것이 먼저다.**
        val three = "🏃‍♂️"

        // 코드포인트 4 라 한 개만으로도 최소 2자를 넘는다
        assertTrue(AuthValidation.isNicknameValid(three))
        // 4 × 4 = 16 > 12
        assertFalse(AuthValidation.isNicknameValid(three.repeat(4)))
    }

    @Test
    fun `한글과 영문은 그대로 한 자다`() {
        assertTrue(AuthValidation.isNicknameValid("러너"))
        assertTrue(AuthValidation.isNicknameValid("김러너입니다열두자야"))
        assertFalse(AuthValidation.isNicknameValid("가"))
        assertFalse(AuthValidation.isNicknameValid("가".repeat(13)))
    }

    @Test
    fun `앞뒤 공백은 세지 않는다`() {
        // 서버도 strip 후에 센다.
        assertTrue(AuthValidation.isNicknameValid("  러너  "))
        assertFalse(AuthValidation.isNicknameValid("  가  "))
    }

    @Test
    fun `내부 공백은 허용한다`() {
        // 문자 종류를 앱이 더 엄격하게 막으면 서버가 받아 줄 닉네임을 앱이 거절한다.
        assertTrue(AuthValidation.isNicknameValid("김 러너"))
    }
}
