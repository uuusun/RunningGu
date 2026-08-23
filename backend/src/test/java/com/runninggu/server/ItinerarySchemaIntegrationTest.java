package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ItinerarySchemaIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE itinerary, contest, app_user RESTART IDENTITY CASCADE");
    }

    @Test
    void 사용자_삭제는_동선_일자_블록을_연쇄삭제한다() {
        long userId = insertUser("삭제러너", "del-runner");
        long contestId = insertContest("delete-itinerary-contest");
        long itineraryId = insertItinerary(userId, contestId, LocalDate.of(2026, 8, 21));
        long dayId = insertDay(itineraryId);
        insertRaceBlock(dayId, contestId);

        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);

        assertThat(count("itinerary")).isZero();
        assertThat(count("itinerary_day")).isZero();
        assertThat(count("itinerary_block")).isZero();
    }

    @Test
    void 저장된_동선이_참조하는_대회는_삭제할_수_없다() {
        long userId = insertUser("참조러너", "ref-runner");
        long contestId = insertContest("referenced-contest");
        insertItinerary(userId, contestId, LocalDate.of(2026, 8, 21));

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM contest WHERE id = ?", contestId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 동일한_여행키와_블록_소유규칙은_DB에서도_강제한다() {
        long userId = insertUser("제약러너", "rule-runner");
        long contestId = insertContest("constraint-contest");
        long itineraryId = insertItinerary(userId, contestId, LocalDate.of(2026, 8, 21));
        long dayId = insertDay(itineraryId);

        assertThatThrownBy(() -> insertItinerary(
                        userId, contestId, LocalDate.of(2026, 8, 21)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        INSERT INTO itinerary_block(
                            day_id, contest_id, block_type, system_managed, order_no,
                            start_time, title, category)
                        VALUES (?, ?, 'USER', true, 0, TIME '09:00', '변조 블록', 'RACE')
                        """,
                        dayId,
                        contestId))
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
                    canonical_key, name, region, place, contest_date, start_time,
                    source_status, category, active, checked_at, updated_at)
                VALUES (?, '스키마 대회', '서울', '스키마 대회장', DATE '2026-08-22',
                    TIME '09:00', 'OPEN', 'ROAD', true, now(), now())
                RETURNING id
                """,
                Long.class,
                canonicalKey);
    }

    private long insertItinerary(long userId, long contestId, LocalDate startDate) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO itinerary(
                    user_id, contest_id, title, event, themes, start_date, end_date,
                    region_snapshot, created_at, updated_at)
                VALUES (?, ?, '스키마 동선', 'HALF', '["TOUR"]'::jsonb, ?, ?,
                    '서울', now(), now())
                RETURNING id
                """,
                Long.class,
                userId,
                contestId,
                startDate,
                startDate.plusDays(2));
    }

    private long insertDay(long itineraryId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO itinerary_day(itinerary_id, day_index, day_date, recovery)
                VALUES (?, 0, DATE '2026-08-22', false) RETURNING id
                """,
                Long.class,
                itineraryId);
    }

    private void insertRaceBlock(long dayId, long contestId) {
        jdbcTemplate.update(
                """
                INSERT INTO itinerary_block(
                    day_id, contest_id, block_type, system_managed, order_no,
                    start_time, title, category)
                VALUES (?, ?, 'RACE', true, 0, TIME '09:00', '대회', 'RACE')
                """,
                dayId,
                contestId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
