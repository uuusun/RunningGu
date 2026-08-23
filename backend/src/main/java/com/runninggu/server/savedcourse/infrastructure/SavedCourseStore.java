package com.runninggu.server.savedcourse.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.savedcourse.application.SaveSavedCourseCommand;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 유일키 경합에서도 기존 snapshot을 갱신하지 않고 저장 id를 돌려준다. */
@Repository
public class SavedCourseStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SavedCourseStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public StoreResult insertOrFind(
            long userId,
            String routeFingerprint,
            SaveSavedCourseCommand command,
            List<String> attributions,
            Instant savedAt) {
        List<Long> insertedIds = jdbcTemplate.queryForList(
                """
                INSERT INTO saved_course(
                    user_id, route_fingerprint, data_source, source_course_id,
                    course_name, region, distance_km, duration_min, difficulty,
                    gain_m, elevation_profile_m, attributions, entry_lat, entry_lng,
                    path_polyline, saved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                    ?, ?, ?, ?)
                ON CONFLICT (user_id, route_fingerprint) DO NOTHING
                RETURNING id
                """,
                Long.class,
                userId,
                routeFingerprint,
                command.dataSource().name(),
                command.sourceCourseId(),
                command.courseName(),
                command.region(),
                command.distanceKm(),
                command.durationMin(),
                command.difficulty() == null ? null : command.difficulty().name(),
                command.gainM(),
                json(command.elevationProfileM()),
                json(attributions),
                command.entryLat(),
                command.entryLng(),
                command.pathPolyline(),
                OffsetDateTime.ofInstant(savedAt, ZoneOffset.UTC));
        if (!insertedIds.isEmpty()) {
            return new StoreResult(insertedIds.getFirst(), true);
        }

        Long existingId = jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM saved_course
                WHERE user_id = ? AND route_fingerprint = ?
                """,
                Long.class,
                userId,
                routeFingerprint);
        if (existingId == null) {
            throw new IllegalStateException("중복 저장 코스 id를 조회할 수 없습니다.");
        }
        return new StoreResult(existingId, false);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("저장 코스 JSON snapshot을 만들 수 없습니다.", exception);
        }
    }

    public record StoreResult(long id, boolean created) {}
}
