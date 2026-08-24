package com.runninggu.app.ui.auth

/**
 * A1~A3 입력 검증. (SPEC §4.2 🔧정책)
 *
 * §5 도메인 규칙이 아니라 화면 입력 규칙이라 `ui/auth` 에 둔다. 서버도 같은 규칙으로
 * 다시 검증한다(명세 §1-5) — 여기는 인라인 안내용이다.
 *
 * **안드로이드 프레임워크에 의존하지 않는다.** `android.util.Patterns` 를 쓰면 JVM 단위
 * 테스트에서 stub 이 null 을 돌려줘 규칙을 테스트로 고정할 수 없다. 비밀번호 규칙은
 * §4.2-2 🔒 값이라 특히 고정해 둘 가치가 있다.
 */
/**
 * 비밀번호가 규칙에 어긋난 사유. (SPEC §4.2-2 🔒)
 *
 * 화면이 문구를 고르는 데 쓴다. 서버는 넷을 한 오류(`INVALID_PASSWORD`)로 합쳐 주지만,
 * 인라인 안내는 **사용자가 지금 뭘 해야 하는지**를 말해야 해서 갈라 둔다.
 */
enum class PasswordIssue {
    /** 8자 미만이거나 ASCII 영문·숫자가 빠졌다. 더 쓰거나 종류를 섞어야 한다. */
    FORMAT,

    /** UTF-8 72바이트를 넘었다. 줄여야 한다. */
    TOO_LONG,
}

object AuthValidation {

    const val NICKNAME_MIN = 2
    const val NICKNAME_MAX = 12
    const val PASSWORD_MIN = 8

    /**
     * 비밀번호 UTF-8 바이트 상한 🔒. (SPEC NFR-9 · 명세 §1-5)
     *
     * BCrypt 가 입력을 72바이트에서 자르기 때문에 서버가 그 위를 거부한다. 길이가 아니라
     * **바이트**라 한글은 한 자에 3바이트씩 먹는다 — 영문 72자, 한글 24자쯤이다.
     */
    const val PASSWORD_MAX_UTF8_BYTES = 72

    const val CODE_LENGTH = 6

    /**
     * 화면 인라인 안내용 이메일 형식. 최종 판정은 서버가 한다(§1-5).
     *
     * 공백 없이 `계정@도메인.최상위`(TLD 2자 이상) 정도만 본다 — 지나치게 엄격한 정규식은
     * 실제로 쓰이는 주소를 막는다.
     */
    private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[A-Za-z]{2,}$""")

    fun isEmailValid(email: String): Boolean = EMAIL.matches(email.trim())

    /**
     * 비밀번호가 규칙에 어긋난 사유. 통과면 `null`. (SPEC §4.2-2 🔒 · 명세 §1-5)
     *
     * 화면이 인라인 문구를 가르는 데 쓴다 — "짧다·영문이 없다" 와 "너무 길다" 는 사용자가
     * 할 일이 정반대라 같은 문구로 뭉치면 안 된다.
     *
     * 순서는 서버 `PasswordPolicy` · 재설정 페이지(#182)와 같다. 형식을 먼저 보고 바이트를 본다.
     */
    fun passwordIssue(password: String): PasswordIssue? {
        // **코드포인트로 센다.** `String.length` 는 UTF-16 코드 단위라 astral 문자가 2로
        // 셌다 — 서버는 `codePointCount` 라 앱이 통과시킨 것을 서버가 거부했다.
        val codePoints = password.codePointCount(0, password.length)
        // **ASCII 만이다.** `isLetter()`·`isDigit()` 은 유니코드 전체라 한글도 "영문" 으로,
        // `٣`(아라비아-인도 숫자)도 "숫자" 로 쳤다. 서버는 `[A-Za-z]`·`[0-9]` 만 본다 —
        // `비밀번호1234` 가 앱에서는 초록불이었다가 가입 마지막에 서버가 튕겼다.
        val hasAsciiLetter = password.any { it in 'A'..'Z' || it in 'a'..'z' }
        val hasAsciiDigit = password.any { it in '0'..'9' }
        if (codePoints < PASSWORD_MIN || !hasAsciiLetter || !hasAsciiDigit) {
            return PasswordIssue.FORMAT
        }
        if (password.toByteArray(Charsets.UTF_8).size > PASSWORD_MAX_UTF8_BYTES) {
            return PasswordIssue.TOO_LONG
        }
        return null
    }

    /** 8자 이상 + ASCII 영문 + 숫자 + UTF-8 72바이트 이하. (SPEC §4.2-2 🔒 · 명세 §1-5) */
    fun isPasswordValid(password: String): Boolean = passwordIssue(password) == null

    /**
     * 2~12자. **코드포인트로 센다.** (명세 §1-2 · 이슈 #97)
     *
     * `String.length` 는 UTF-16 코드 단위라 이모지가 2자 이상으로 셌다 — `🏃` 는 2,
     * `🏃‍♂️`(ZWJ 조합)는 5 다. 서버(`NicknamePolicy`)가 `codePointCount` 로 세므로
     * 그대로 두면 **같은 닉네임을 앱은 막고 서버는 받는다.** 사용자에게는 "12자 이내인데
     * 왜 안 되지" 로만 보인다.
     *
     * 자소 클러스터가 사람 직관에 제일 가깝지만 `BreakIterator` 가 필요해 P0 에는 과하다 —
     * 서버와 같은 기준으로 맞추는 것이 먼저다.
     */
    fun isNicknameValid(nickname: String): Boolean {
        val trimmed = nickname.trim()
        return trimmed.codePointCount(0, trimmed.length) in NICKNAME_MIN..NICKNAME_MAX
    }

    fun isCodeValid(code: String): Boolean =
        code.length == CODE_LENGTH && code.all { it.isDigit() }
}
