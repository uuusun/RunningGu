package com.runninggu.server.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VerificationCodeSecurityTest {

    @Test
    void 생성한_코드는_항상_6자리_숫자다() {
        SecureVerificationCodeGenerator generator = new SecureVerificationCodeGenerator();
        Set<String> generated = new HashSet<>();

        for (int count = 0; count < 100; count++) {
            String code = generator.generate();
            assertThat(code).matches("^[0-9]{6}$");
            generated.add(code);
        }

        assertThat(generated).hasSizeGreaterThan(1);
    }

    @Test
    void 코드는_BCrypt_strength_10으로_해시하고_원문과_일치여부를_검증한다() {
        BCryptVerificationCodeHasher hasher = new BCryptVerificationCodeHasher();

        String hash = hasher.hash("001234");

        assertThat(hash).startsWith("$2a$10$");
        assertThat(hash).doesNotContain("001234");
        assertThat(hasher.matches("001234", hash)).isTrue();
        assertThat(hasher.matches("001235", hash)).isFalse();
    }
}
