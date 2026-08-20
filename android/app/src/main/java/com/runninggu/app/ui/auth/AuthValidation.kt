package com.runninggu.app.ui.auth

import android.util.Patterns

/**
 * A1~A3 입력 검증. (SPEC §4.2 🔧정책)
 *
 * §5 도메인 규칙이 아니라 화면 입력 규칙이라 `ui/auth` 에 둔다. 서버도 같은 규칙으로
 * 다시 검증한다(명세 §1-5) — 여기는 인라인 안내용이다.
 */
object AuthValidation {

    const val NICKNAME_MIN = 2
    const val NICKNAME_MAX = 12
    const val PASSWORD_MIN = 8
    const val CODE_LENGTH = 6

    fun isEmailValid(email: String): Boolean =
        email.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(email).matches()

    /** 8자 이상 + 영문 + 숫자. (SPEC §4.2-2 · 명세 §1-5) */
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
