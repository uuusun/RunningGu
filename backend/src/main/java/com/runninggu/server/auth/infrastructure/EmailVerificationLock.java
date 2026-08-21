package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.domain.EmailVerificationPurpose;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class EmailVerificationLock {

    private final JdbcTemplate jdbcTemplate;

    public EmailVerificationLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 아직 행이 없는 첫 발송도 직렬화하도록 PostgreSQL 트랜잭션 잠금을 사용한다. */
    public void lock(String email, EmailVerificationPurpose purpose) {
        long key = lockKey(purpose.name() + '\0' + email);
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
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
