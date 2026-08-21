package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.application.VerificationCodeHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BCryptVerificationCodeHasher implements VerificationCodeHasher {

    private static final int STRENGTH = 10;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(STRENGTH);

    @Override
    public String hash(String code) {
        return encoder.encode(code);
    }

    @Override
    public boolean matches(String code, String hash) {
        return encoder.matches(code, hash);
    }
}
