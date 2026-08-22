package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AuthSchemaIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE email_verification, login_identity, app_user RESTART IDENTITY CASCADE");
    }

    @Test
    void 닉네임_중복키는_DB에서도_유일하다() {
        insertUser("Runner", "runner");

        assertThatThrownBy(() -> insertUser("RUNNER", "runner"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 로그인_주체는_사용자당_하나이고_provider_subject_조합이_유일하다() {
        long firstUser = insertUser("첫번째", "첫번째");
        long secondUser = insertUser("두번째", "두번째");
        insertEmailIdentity(firstUser, "runner@example.com");

        assertThatThrownBy(() -> insertKakaoIdentity(firstUser, "kakao-first"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEmailIdentity(secondUser, "runner@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void EMAIL과_KAKAO별_필수_필드_조합을_DB에서_강제한다() {
        long emailUser = insertUser("이메일", "이메일");
        long kakaoUser = insertUser("카카오", "카카오");

        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO login_identity(user_id, provider, provider_subject, created_at)
                        VALUES (?, 'EMAIL', 'broken@example.com', CURRENT_TIMESTAMP)
                        """,
                        emailUser))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO login_identity(
                            user_id, provider, provider_subject, password_hash, created_at)
                        VALUES (?, 'KAKAO', 'kakao-broken', 'must-be-null', CURRENT_TIMESTAMP)
                        """,
                        kakaoUser))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 인증은_이메일과_목적당_한_행이며_시도횟수와_해시_조합을_강제한다() {
        insertSignupVerification("runner@example.com", 0);

        assertThatThrownBy(() -> insertSignupVerification("runner@example.com", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSignupVerification("other@example.com", 6))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO email_verification(
                            email, purpose, code_hash, attempts, sent_at, expires_at)
                        VALUES ('reset@example.com', 'PASSWORD_RESET', 'wrong-hash', 0,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                        """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 리프레시는_family당_활성_한개만_허용하고_회전_이력은_보존한다() {
        long userId = insertUser("세션러너", "세션러너");
        String familyId = "11111111-1111-1111-1111-111111111111";
        insertRefresh(userId, familyId, "a".repeat(64));

        assertThatThrownBy(() -> insertRefresh(userId, familyId, "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update(
                "UPDATE refresh_token SET revoked_at = CURRENT_TIMESTAMP WHERE token_hash = ?",
                "a".repeat(64));
        insertRefresh(userId, familyId, "c".repeat(64));
    }

    @Test
    void 사용자_삭제는_약관과_리프레시를_함께_삭제한다() {
        long userId = insertUser("삭제러너", "삭제러너");
        jdbcTemplate.update(
                """
                INSERT INTO user_agreement(user_id, agreement_type, version, agreed, changed_at)
                VALUES (?, 'TOS', '1.0', true, CURRENT_TIMESTAMP)
                """,
                userId);
        insertRefresh(
                userId,
                "22222222-2222-2222-2222-222222222222",
                "d".repeat(64));

        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_agreement",
                Integer.class)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token",
                Integer.class)).isZero();
    }

    private long insertUser(String nickname, String nicknameKey) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO app_user(nickname, nickname_key, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                nickname,
                nicknameKey);
    }

    private void insertEmailIdentity(long userId, String email) {
        jdbcTemplate.update(
                """
                INSERT INTO login_identity(
                    user_id, provider, provider_subject, password_hash,
                    email_verified_at, created_at)
                VALUES (?, 'EMAIL', ?, 'password-hash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                userId,
                email);
    }

    private void insertKakaoIdentity(long userId, String subject) {
        jdbcTemplate.update(
                """
                INSERT INTO login_identity(user_id, provider, provider_subject, created_at)
                VALUES (?, 'KAKAO', ?, CURRENT_TIMESTAMP)
                """,
                userId,
                subject);
    }

    private void insertSignupVerification(String email, int attempts) {
        jdbcTemplate.update(
                """
                INSERT INTO email_verification(
                    email, purpose, code_hash, attempts, sent_at, expires_at)
                VALUES (?, 'SIGNUP', 'bcrypt-hash', ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                """,
                email,
                attempts);
    }

    private void insertRefresh(long userId, String familyId, String tokenHash) {
        jdbcTemplate.update(
                """
                INSERT INTO refresh_token(
                    user_id, family_id, token_hash, expires_at, created_at)
                VALUES (?, CAST(? AS UUID), ?, CURRENT_TIMESTAMP + INTERVAL '14 days',
                    CURRENT_TIMESTAMP)
                """,
                userId,
                familyId,
                tokenHash);
    }
}
