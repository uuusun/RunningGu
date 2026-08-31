package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.domain.LoginProvider;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LoginIdentityLock {

    private final JdbcTemplate jdbcTemplate;

    public LoginIdentityLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 아직 행이 없는 최초 가입도 LOGIN_IDENTITY 순서에서 직렬화한다. (SPEC §6.5, 결정-57) */
    public void lock(LoginProvider provider, String providerSubject) {
        long key = lockKey(provider.name() + '\0' + providerSubject);
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, key);
                statement.execute();
                return null;
            }
        });
    }

    private long lockKey(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
