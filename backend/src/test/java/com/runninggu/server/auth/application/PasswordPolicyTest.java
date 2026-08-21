package com.runninggu.server.auth.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void 영문과_숫자를_포함한_8자부터_UTF8_72바이트까지_허용한다() {
        assertThatCode(() -> policy.validate("run4life"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("a1" + "x".repeat(70)))
                .doesNotThrowAnyException();
    }

    @Test
    void 형식이나_BCrypt_상한을_벗어나면_INVALID_PASSWORD다() {
        assertInvalid("password");
        assertInvalid("12345678");
        assertInvalid("a1short");
        assertInvalid("a1" + "x".repeat(71));
        assertInvalid("a1" + "가".repeat(24));
    }

    private void assertInvalid(String password) {
        assertThatThrownBy(() -> policy.validate(password))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.INVALID_PASSWORD));
    }
}
