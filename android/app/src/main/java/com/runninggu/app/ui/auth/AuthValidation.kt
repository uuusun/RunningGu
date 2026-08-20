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
object AuthValidation {

    const val NICKNAME_MIN = 2
    const val NICKNAME_MAX = 12
    const val PASSWORD_MIN = 8
    const val CODE_LENGTH = 6

    /**
     * 화면 인라인 안내용 이메일 형식. 최종 판정은 서버가 한다(§1-5).
     *
     * 공백 없이 `계정@도메인.최상위`(TLD 2자 이상) 정도만 본다 — 지나치게 엄격한 정규식은
     * 실제로 쓰이는 주소를 막는다.
     */
    private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[A-Za-z]{2,}$""")

    fun isEmailValid(email: String): Boolean = EMAIL.matches(email.trim())

    /** 8자 이상 + 영문 + 숫자. (SPEC §4.2-2 🔒 · 명세 §1-5) */
    fun isPasswordValid(password: String): Boolean =
        password.length >= PASSWORD_MIN &&
            password.any { it.isLetter() } &&
            password.any { it.isDigit() }

    /** 2~12자. (명세 §1-2) */
    fun isNicknameValid(nickname: String): Boolean =
        nickname.trim().length in NICKNAME_MIN..NICKNAME_MAX

    fun isCodeValid(code: String): Boolean =
        code.length == CODE_LENGTH && code.all { it.isDigit() }
}
