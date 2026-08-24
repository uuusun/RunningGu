package com.runninggu.server;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.auth.application.TokenIssuer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ItineraryPersistenceApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenIssuer tokenIssuer;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE itinerary, contest, app_user RESTART IDENTITY CASCADE");
    }

    @Test
    void 저장_목록_상세_동일여행교체와_canonical_변경배지를_처리한다() throws Exception {
        long userId = insertUser("저장러너", "saved-runner");
        long contestId = insertContest("sejong-running", "세종호수공원");
        String accessToken = accessToken(userId);

        MvcResult created = mockMvc.perform(post("/api/itineraries")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(contestId, "첫 관광")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.replaced").doesNotExist())
                .andReturn();
        long itineraryId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("id")
                .asLong();

        assertCanonicalRaceStored(itineraryId);

        mockMvc.perform(get("/api/itineraries")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(itineraryId))
                .andExpect(jsonPath("$.content[0].contestName").value("세종 러닝 페스티벌"))
                .andExpect(jsonPath("$.content[0].region").value("세종"))
                .andExpect(jsonPath("$.content[0].placeCount").value(5))
                .andExpect(jsonPath("$.content[0].needsRegeneration").value(false))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        mockMvc.perform(get("/api/itineraries/{id}", itineraryId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[1].blocks[0].category").value("RACE"))
                .andExpect(jsonPath("$.days[1].blocks[0].placeName").value("세종호수공원"))
                .andExpect(jsonPath("$.days[1].blocks[0].startTime").value("09:00"))
                .andExpect(jsonPath("$.days[1].blocks[0].orderNo").value(0))
                .andExpect(jsonPath("$.days[1].blocks[0].systemManaged").value(true))
                .andExpect(jsonPath("$.contest.place").value("세종호수공원"));

        mockMvc.perform(post("/api/itineraries")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(contestId, "교체 관광")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(itineraryId))
                .andExpect(jsonPath("$.replaced").value(true));

        mockMvc.perform(get("/api/itineraries/{id}", itineraryId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[1].blocks[1].title").value("교체 관광"));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM itinerary",
                Integer.class)).isEqualTo(1);

        jdbcTemplate.update(
                "UPDATE contest SET place = '변경된 대회장', updated_at = now() WHERE id = ?",
                contestId);
        mockMvc.perform(get("/api/itineraries/{id}", itineraryId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsRegeneration").value(true))
                .andExpect(jsonPath("$.contest.place").value("변경된 대회장"))
                .andExpect(jsonPath("$.days[1].blocks[0].placeName").value("세종호수공원"));
    }

    @Test
    void USER_블록을_추가_부분수정_삭제_재정렬하고_RACE는_모두_거부한다() throws Exception {
        long userId = insertUser("편집러너", "edit-runner");
        long contestId = insertContest("edit-running", "편집 대회장");
        String accessToken = accessToken(userId);
        long itineraryId = save(accessToken, contestId, "첫 관광");
        JsonNode detail = details(accessToken, itineraryId);
        long dayId = detail.path("days").get(1).path("id").asLong();
        long raceId = detail.path("days").get(1).path("blocks").get(0).path("id").asLong();
        long firstUserId = detail.path("days").get(1).path("blocks").get(1).path("id").asLong();
        long secondUserId = detail.path("days").get(1).path("blocks").get(2).path("id").asLong();

        MvcResult added = mockMvc.perform(post(
                                "/api/itineraries/{id}/days/{dayId}/blocks", itineraryId, dayId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "추가 카페",
                                  "category": "CAFE",
                                  "placeName": "카페 이름",
                                  "lat": 36.5,
                                  "lng": 127.3
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blockId").isNumber())
                .andExpect(jsonPath("$.orderNo").value(3))
                .andExpect(jsonPath("$.startTime").doesNotExist())
                .andReturn();
        long addedId = objectMapper.readTree(added.getResponse().getContentAsString())
                .path("blockId")
                .asLong();

        mockMvc.perform(patch(
                                "/api/itineraries/{id}/days/{dayId}/blocks/{blockId}",
                                itineraryId,
                                dayId,
                                addedId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "장소 없는 휴식",
                                  "placeName": null,
                                  "lat": null,
                                  "lng": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("장소 없는 휴식"))
                .andExpect(jsonPath("$.placeName").value(nullValue()))
                .andExpect(jsonPath("$.lat").value(nullValue()));

        assertProblem(patch(
                        "/api/itineraries/{id}/days/{dayId}/blocks/{blockId}",
                        itineraryId,
                        dayId,
                        raceId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"변조\"}"),
                409,
                "SYSTEM_BLOCK_IMMUTABLE");
        assertProblem(delete(
                        "/api/itineraries/{id}/days/{dayId}/blocks/{blockId}",
                        itineraryId,
                        dayId,
                        raceId)
                        .header("Authorization", bearer(accessToken)),
                409,
                "SYSTEM_BLOCK_IMMUTABLE");

        assertProblem(put("/api/itineraries/{id}/days/{dayId}/blocks/order", itineraryId, dayId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockIds\":[" + raceId + "]}"),
                409,
                "SYSTEM_BLOCK_IMMUTABLE");

        assertProblem(put("/api/itineraries/{id}/days/{dayId}/blocks/order", itineraryId, dayId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockIds\":[" + firstUserId + "]}"),
                400,
                "BLOCK_SET_MISMATCH");

        mockMvc.perform(put("/api/itineraries/{id}/days/{dayId}/blocks/order", itineraryId, dayId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockIds\":[" + addedId + "," + secondUserId + "," + firstUserId + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].id").value(raceId))
                .andExpect(jsonPath("$.blocks[0].orderNo").value(0))
                .andExpect(jsonPath("$.blocks[1].id").value(addedId))
                .andExpect(jsonPath("$.blocks[2].id").value(secondUserId))
                .andExpect(jsonPath("$.blocks[3].id").value(firstUserId));

        mockMvc.perform(delete(
                                "/api/itineraries/{id}/days/{dayId}/blocks/{blockId}",
                                itineraryId,
                                dayId,
                                addedId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/itineraries/{id}", itineraryId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[1].blocks", hasSize(3)));
    }

    @Test
    void USER_블록이_RACE_고정_경계를_넘으면_순서변경을_거부하고_원래순서를_유지한다() throws Exception {
        long userId = insertUser("경계러너", "boundary");
        long contestId = insertContest("boundary-running", "경계 대회장");
        String accessToken = accessToken(userId);
        long itineraryId = save(accessToken, contestId, "첫 관광");
        JsonNode detail = details(accessToken, itineraryId);
        long dayId = detail.path("days").get(1).path("id").asLong();
        long raceId = detail.path("days").get(1).path("blocks").get(0).path("id").asLong();
        long firstUserId = detail.path("days").get(1).path("blocks").get(1).path("id").asLong();
        long secondUserId = detail.path("days").get(1).path("blocks").get(2).path("id").asLong();

        jdbcTemplate.update(
                """
                UPDATE itinerary_block
                SET order_no = CASE id
                    WHEN ? THEN 1
                    WHEN ? THEN 0
                    ELSE order_no
                END
                WHERE id IN (?, ?)
                """,
                raceId,
                firstUserId,
                raceId,
                firstUserId);

        assertProblem(put("/api/itineraries/{id}/days/{dayId}/blocks/order", itineraryId, dayId)
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockIds\":[" + secondUserId + "," + firstUserId + "]}"),
                409,
                "SYSTEM_BLOCK_IMMUTABLE");

        mockMvc.perform(get("/api/itineraries/{id}", itineraryId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[1].blocks[0].id").value(firstUserId))
                .andExpect(jsonPath("$.days[1].blocks[1].id").value(raceId))
                .andExpect(jsonPath("$.days[1].blocks[2].id").value(secondUserId));
    }

    @Test
    void 소유권과_재생성contestId를_검증하고_삭제한다() throws Exception {
        long ownerId = insertUser("소유러너", "owner-runner");
        long otherId = insertUser("다른러너", "other-runner");
        long contestId = insertContest("owner-running", "원래 대회장");
        long otherContestId = insertContest("other-running", "다른 대회장");
        String ownerToken = accessToken(ownerId);
        String otherToken = accessToken(otherId);
        long itineraryId = save(ownerToken, contestId, "소유 관광");

        assertProblem(get("/api/itineraries/{id}", itineraryId)
                        .header("Authorization", bearer(otherToken)),
                403,
                "FORBIDDEN");

        assertProblem(put("/api/itineraries/{id}", itineraryId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(otherContestId, "다른 관광")),
                400,
                "VALIDATION_FAILED");
        mockMvc.perform(get("/api/itineraries/{id}", itineraryId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[1].blocks[1].title").value("소유 관광"));

        mockMvc.perform(delete("/api/itineraries/{id}", itineraryId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNoContent());
        assertProblem(get("/api/itineraries/{id}", itineraryId)
                        .header("Authorization", bearer(ownerToken)),
                404,
                "ITINERARY_NOT_FOUND");
    }

    @Test
    void 미인증_저장과_잘못된_기간_페이지_블록집합을_계약오류로_응답한다() throws Exception {
        long userId = insertUser("검증러너", "valid-runner");
        long contestId = insertContest("validation-running", "검증 대회장");
        String accessToken = accessToken(userId);

        mockMvc.perform(post("/api/itineraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(contestId, "관광")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertProblem(post("/api/itineraries")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(contestId, "관광")
                                .replace("2026-08-21", "2026-08-15")),
                400,
                "INVALID_TRAVEL_PERIOD");
        assertProblem(get("/api/itineraries")
                        .header("Authorization", bearer(accessToken))
                        .param("size", "51"),
                400,
                "VALIDATION_FAILED");
    }

    private long save(String accessToken, long contestId, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/itineraries")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(contestId, title)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private JsonNode details(String accessToken, long itineraryId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/itineraries/{id}", itineraryId)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertCanonicalRaceStored(long itineraryId) {
        var race = jdbcTemplate.queryForMap(
                """
                SELECT b.block_type, b.system_managed, b.order_no, b.title,
                       b.place_name, b.address, b.start_time
                FROM itinerary_block b
                JOIN itinerary_day d ON d.id = b.day_id
                WHERE d.itinerary_id = ? AND b.block_type = 'RACE'
                """,
                itineraryId);
        org.assertj.core.api.Assertions.assertThat(race.get("system_managed")).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(race.get("order_no")).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(race.get("title")).isEqualTo("🏁 세종 러닝 페스티벌 스타트");
        org.assertj.core.api.Assertions.assertThat(race.get("place_name")).isEqualTo("세종호수공원");
        org.assertj.core.api.Assertions.assertThat(race.get("address")).isEqualTo("세종특별자치시 다솜로 216");
    }

    private void assertProblem(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            int statusCode,
            String code) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is(statusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    private long insertUser(String nickname, String nicknameKey) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO app_user (nickname, nickname_key, created_at, updated_at)
                VALUES (?, ?, now(), now()) RETURNING id
                """,
                Long.class,
                nickname,
                nicknameKey);
    }

    private long insertContest(String canonicalKey, String place) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO contest (
                    canonical_key, name, region, place, road_address, lat, lng,
                    contest_date, start_time, source_status, category, active,
                    checked_at, updated_at
                ) VALUES (
                    ?, '세종 러닝 페스티벌', '세종', ?, '세종특별자치시 다솜로 216',
                    36.4912000, 127.2714000, DATE '2026-08-22', TIME '09:00',
                    'OPEN', 'ROAD', true, now(), now()
                ) RETURNING id
                """,
                Long.class,
                canonicalKey,
                place);
    }

    private String accessToken(long userId) {
        return tokenIssuer.issue(userId, UUID.randomUUID(), Instant.now()).accessToken();
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private String saveBody(long contestId, String firstTitle) {
        return """
                {
                  "title": "조작된 제목",
                  "contestId": %d,
                  "event": "HALF",
                  "themes": ["TOUR", "FOOD"],
                  "startDate": "2026-08-21",
                  "endDate": "2026-08-23",
                  "hotel": {"name":"호텔 세종", "lat":36.4901, "lng":127.2688},
                  "recovery": {"label":"조작된 회복", "note":"조작된 노트"},
                  "days": [
                    {
                      "dayIndex": -1,
                      "date": "2026-08-21",
                      "recovery": false,
                      "note": "전날",
                      "blocks": [
                        {"startTime":"15:00", "title":"숙소 체크인", "category":"LODGING",
                         "placeName":"호텔 세종", "lat":36.4901, "lng":127.2688,
                         "blockType":"USER", "systemManaged":false}
                      ]
                    },
                    {
                      "dayIndex": 0,
                      "date": "2026-08-22",
                      "recovery": false,
                      "note": "대회일",
                      "blocks": [
                        {"startTime":"01:00", "title":"변조 RACE", "category":"RACE",
                         "placeName":"가짜 대회장", "lat":1, "lng":1,
                         "blockType":"RACE", "systemManaged":true},
                        {"startTime":"14:00", "title":"%s", "category":"TOUR",
                         "placeName":"관광지 1", "lat":36.5, "lng":127.3,
                         "blockType":"USER", "systemManaged":false},
                        {"startTime":"16:00", "title":"두 번째 관광", "category":"HISTORY",
                         "placeName":"관광지 2", "lat":36.51, "lng":127.31,
                         "blockType":"USER", "systemManaged":false}
                      ]
                    },
                    {
                      "dayIndex": 1,
                      "date": "2026-08-23",
                      "recovery": true,
                      "note": "회복일",
                      "blocks": [
                        {"startTime":"12:30", "title":"로컬 점심", "category":"FOOD",
                         "placeName":"식당", "lat":36.52, "lng":127.32,
                         "blockType":"USER", "systemManaged":false}
                      ]
                    }
                  ]
                }
                """.formatted(contestId, firstTitle);
    }
}
