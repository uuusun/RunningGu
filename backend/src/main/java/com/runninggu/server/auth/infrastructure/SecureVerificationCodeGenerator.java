package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.application.VerificationCodeGenerator;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class SecureVerificationCodeGenerator implements VerificationCodeGenerator {

    private static final int CODE_BOUND = 1_000_000;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generate() {
        return "%06d".formatted(secureRandom.nextInt(CODE_BOUND));
    }
}
