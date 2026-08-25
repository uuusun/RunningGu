package com.runninggu.server.auth.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.runninggu.server.auth.application.PasswordPolicyCases#validCases")
    void 영문과_숫자를_포함한_8자부터_UTF8_72바이트까지_허용한다(
            PasswordPolicyCases.PasswordCase passwordCase) {
        assertThatCode(() -> policy.validate(passwordCase.password()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.runninggu.server.auth.application.PasswordPolicyCases#invalidCases")
    void 형식이나_BCrypt_상한을_벗어나면_INVALID_PASSWORD다(
            PasswordPolicyCases.PasswordCase passwordCase) {
        assertInvalid(passwordCase.password());
    }

    private void assertInvalid(String password) {
        assertThatThrownBy(() -> policy.validate(password))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.INVALID_PASSWORD));
    }
}
