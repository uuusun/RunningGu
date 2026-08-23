package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class FavoriteSchemaIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE favorite, itinerary, contest, app_user RESTART IDENTITY CASCADE");
    }

    @Test
    void 사용자와_대회_조합은_DB에서도_유일하다() {
        long userId = insertUser("유일러너", "unique-fav");
        long contestId = insertContest("unique-favorite");
        insertFavorite(userId, contestId);

        assertThatThrownBy(() -> insertFavorite(userId, contestId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 사용자_삭제는_찜을_연쇄삭제한다() {
        long userId = insertUser("삭제러너", "delete-fav");
        long contestId = insertContest("delete-favorite");
        insertFavorite(userId, contestId);

        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite", Integer.class)).isZero();
    }

    @Test
    void 찜이_참조하는_대회는_삭제할_수_없다() {
        long userId = insertUser("참조러너", "ref-fav");
        long contestId = insertContest("referenced-favorite");
        insertFavorite(userId, contestId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM contest WHERE id = ?", contestId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertUser(String nickname, String nicknameKey) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO app_user(nickname, nickname_key, created_at, updated_at)
                VALUES (?, ?, now(), now()) RETURNING id
                """,
                Long.class,
                nickname,
                nicknameKey);
    }

    private long insertContest(String canonicalKey) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO contest(
                    canonical_key, name, region, place, contest_date,
                    source_status, category, active, checked_at, updated_at)
                VALUES (?, '찜 스키마 대회', '서울', '서울광장', DATE '2026-09-01',
                    'OPEN', 'ROAD', true, now(), now())
                RETURNING id
                """,
                Long.class,
                canonicalKey);
    }

    private void insertFavorite(long userId, long contestId) {
        jdbcTemplate.update(
                """
                INSERT INTO favorite(user_id, contest_id, created_at)
                VALUES (?, ?, now())
                """,
                userId,
                contestId);
    }
}
