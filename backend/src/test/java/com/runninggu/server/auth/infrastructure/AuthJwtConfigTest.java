package com.runninggu.server.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthJwtConfigTest {

    private final AuthJwtConfig config = new AuthJwtConfig();

    @Test
    void JWT_시크릿이_누락되거나_Base64가_아니거나_32바이트_미만이면_기동을_거부한다() {
        assertInvalid(null, "JWT_SECRET이 필요합니다.");
        assertInvalid("not-base64", "올바른 Base64");
        assertInvalid("c2hvcnQ=", "32바이트 이상");
    }

    private void assertInvalid(String secret, String message) {
        JwtProperties properties = new JwtProperties(
                secret,
                "runninggu",
                "runninggu-api",
                Duration.ofMinutes(30),
                Duration.ofDays(14));
        assertThatThrownBy(() -> config.jwtSecretKey(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }
}
