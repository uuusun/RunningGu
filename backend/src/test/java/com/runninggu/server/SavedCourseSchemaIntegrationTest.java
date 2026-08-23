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
class SavedCourseSchemaIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE TABLE saved_course, app_user RESTART IDENTITY CASCADE");
    }

    @Test
    void 사용자별_fingerprint는_유일하고_사용자_삭제시_연쇄삭제한다() {
        long userId = insertUser("스키마러너", "schema");
        insertSavedCourse(userId, fingerprint('a'), "[]");

        assertThatThrownBy(() -> insertSavedCourse(userId, fingerprint('a'), "[]"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM saved_course", Integer.class)).isZero();
    }

    @Test
    void 고도_JSONB는_배열_최대100개만_허용한다() {
        long userId = insertUser("고도러너", "elevation");
        insertSavedCourse(userId, fingerprint('a'), "[]");
        insertSavedCourse(userId, fingerprint('b'), "[1,2,3]");

        assertThatThrownBy(() -> insertSavedCourse(userId, fingerprint('c'), "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSavedCourse(
                        userId,
                        fingerprint('d'),
                        "[0" + ",0".repeat(100) + "]"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 목록_정렬용_복합인덱스가_존재한다() {
        Integer indexCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = 'saved_course'
                  AND indexname = 'idx_saved_course_user_saved'
                  AND indexdef LIKE '%(user_id, saved_at DESC, id DESC)%'
                """,
                Integer.class);

        assertThat(indexCount).isEqualTo(1);
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

    private void insertSavedCourse(long userId, String fingerprint, String elevationJson) {
        jdbcTemplate.update(
                """
                INSERT INTO saved_course(
                    user_id, route_fingerprint, data_source, course_name,
                    distance_km, duration_min, gain_m, elevation_profile_m,
                    entry_lat, entry_lng, path_polyline, saved_at)
                VALUES (?, ?, 'OSM_GENERATED', '스키마 코스', 5.000, 45, 10,
                    CAST(? AS jsonb), 37.1234567, 127.1234567, '??', now())
                """,
                userId,
                fingerprint,
                elevationJson);
    }

    private String fingerprint(char value) {
        return "v1:" + String.valueOf(value).repeat(64);
    }
}
