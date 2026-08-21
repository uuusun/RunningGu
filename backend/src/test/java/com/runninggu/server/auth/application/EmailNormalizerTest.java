package com.runninggu.server.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

class EmailNormalizerTest {

    private final EmailNormalizer normalizer = new EmailNormalizer();

    @Test
    void 양끝_공백을_제거하고_ROOT_규칙으로_소문자화한다() {
        assertThat(normalizer.normalize("  USER@Example.COM  "))
                .isEqualTo("user@example.com");
    }

    @Test
    void 공백_at_도메인_점_ASCII_TLD_규칙을_검증한다() {
        assertInvalid("user example.com");
        assertInvalid("user.example.com");
        assertInvalid("user@example");
        assertInvalid("user@example.한국");
        assertInvalid("user@example.c");
    }

    @Test
    void 최대_320_코드포인트를_허용한다() {
        String localPart = "a".repeat(308);

        assertThat(normalizer.normalize(localPart + "@example.com"))
                .hasSize(320);
        assertInvalid("a".repeat(309) + "@example.com");
    }

    private void assertInvalid(String value) {
        assertThatThrownBy(() -> normalizer.normalize(value))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
