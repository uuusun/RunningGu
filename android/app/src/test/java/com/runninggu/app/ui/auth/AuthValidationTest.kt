package com.runninggu.app.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ── 비밀번호 ─────────────────────────────────────────────
    //
    // 서버 `PasswordPolicy` 와 **같은 입력이 같은 답을 내야 한다.** 여기가 갈리면
    // 화면은 초록불인데 가입 마지막에 서버가 튕긴다 — 사용자는 뭘 고쳐야 할지 모른다.

    @Test
    fun `한글만 있는 비밀번호는 영문이 아니다`() {
        // `isLetter()` 는 유니코드 letter 라 한글도 "영문" 으로 쳤다. 서버는 `[A-Za-z]` 다.
        // 한국어 앱에서 제일 나오기 쉬운 입력이라 이게 제일 자주 터질 자리였다.
        assertFalse(AuthValidation.isPasswordValid("비밀번호1234"))
        assertEquals(PasswordIssue.FORMAT, AuthValidation.passwordIssue("비밀번호1234"))
    }

    @Test
    fun `유니코드 숫자는 숫자로 치지 않는다`() {
        // `isDigit()` 은 Nd 전체라 아라비아-인도 숫자도 통과했다. 서버는 `[0-9]` 다.
        assertFalse(AuthValidation.isPasswordValid("password\u0663\u0664\u0665"))
    }

    @Test
    fun `길이는 코드포인트로 센다`() {
        // **길이만 갈리는 입력이어야 한다.** ASCII 영문·숫자를 넣어 두지 않으면 길이를
        // 되돌려도 영문·숫자 규칙에서 걸려서, 테스트가 통과해도 길이를 지킨 게 아니다.
        //
        // a1😀😀😀 — 코드포인트 5(8 미만이라 거부), UTF-16 코드 단위 8(세면 통과해 버린다).
        val emoji = "a1\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"
        assertEquals(8, emoji.length)
        assertEquals(5, emoji.codePointCount(0, emoji.length))
        assertTrue("전제 — ASCII 영문·숫자는 들어 있다", emoji.any { it in 'a'..'z' } && emoji.any { it in '0'..'9' })

        assertFalse("UTF-16 길이로 세면 통과한다", AuthValidation.isPasswordValid(emoji))
        assertEquals(PasswordIssue.FORMAT, AuthValidation.passwordIssue(emoji))
    }

    @Test
    fun `UTF-8 72바이트를 넘으면 거부한다`() {
        // BCrypt 가 72바이트에서 자르기 때문에 서버가 그 위를 막는다(NFR-9 🔒).
        // 앱에는 이 규칙이 아예 없어서 서버만 거부했다.
        val long = "a1" + "가".repeat(24) // 2 + 72 = 74바이트
        assertEquals(74, long.toByteArray(Charsets.UTF_8).size)
        assertFalse(AuthValidation.isPasswordValid(long))
        assertEquals(PasswordIssue.TOO_LONG, AuthValidation.passwordIssue(long))
    }

    @Test
    fun `정확히 72바이트는 통과한다`() {
        // 경계는 이하(<=)다. 서버 `MAX_UTF8_BYTES` 와 같은 방향이어야 한다.
        val exact = "a1" + "가".repeat(23) + "b" // 2 + 69 + 1 = 72바이트
        assertEquals(72, exact.toByteArray(Charsets.UTF_8).size)
        assertTrue(AuthValidation.isPasswordValid(exact))
    }

    @Test
    fun `8자 이상 영문과 숫자면 통과한다`() {
        assertTrue(AuthValidation.isPasswordValid("newRun4life"))
        assertNull(AuthValidation.passwordIssue("newRun4life"))
        // 경계 — 정확히 8자
        assertTrue(AuthValidation.isPasswordValid("abcdefg1"))
        assertFalse(AuthValidation.isPasswordValid("abcdef1"))
    }

    @Test
    fun `영문이나 숫자 한쪽만 있으면 거부한다`() {
        assertFalse(AuthValidation.isPasswordValid("abcdefghij"))
        assertFalse(AuthValidation.isPasswordValid("1234567890"))
    }

    // ── 닉네임 ─────────────────────────────────────────────

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
