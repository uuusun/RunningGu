package com.runninggu.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void OpenAPI_문서를_공개한다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("런닝구 API"))
                .andExpect(jsonPath("$.info.version").value("v3.1"))
                .andExpect(jsonPath("$['paths']['/api/contests']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/contests/daily-counts']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/contests/closing-soon']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/contests/{id}']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/courses']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/courses/regions']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/email/exists']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/nickname/exists']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/email/send-code']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/email/verify']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/signup']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/login']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/kakao']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/kakao/signup']['post']").exists())
                .andExpect(jsonPath(
                                "$['paths']['/api/auth/kakao']['post']['responses']['200']"
                                        + "['content']['application/json']['schema']['oneOf']")
                        .isArray())
                .andExpect(jsonPath(
                                "$['paths']['/api/auth/kakao/signup']['post']['responses']['201']"
                                        + "['content']['application/json']['schema']")
                        .exists())
                .andExpect(jsonPath("$['paths']['/api/auth/refresh']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/auth/logout']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/itineraries/generate']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/itineraries']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/itineraries']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/itineraries/{id}']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/itineraries/{id}']['put']").exists())
                .andExpect(jsonPath("$['paths']['/api/itineraries/{id}']['delete']").exists())
                .andExpect(jsonPath(
                                "$['paths']['/api/itineraries/{id}/days/{dayId}/blocks']['post']")
                        .exists())
                .andExpect(jsonPath(
                                "$['paths']['/api/itineraries/{id}/days/{dayId}/blocks/{blockId}']['patch']")
                        .exists())
                .andExpect(jsonPath(
                                "$['paths']['/api/itineraries/{id}/days/{dayId}/blocks/{blockId}']['delete']")
                        .exists())
                .andExpect(jsonPath(
                                "$['paths']['/api/itineraries/{id}/days/{dayId}/blocks/order']['put']")
                        .exists())
                .andExpect(jsonPath("$['paths']['/api/me/favorites']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/me/favorites/{contestId}']['put']").exists())
                .andExpect(jsonPath("$['paths']['/api/me/favorites/{contestId}']['delete']").exists())
                .andExpect(jsonPath("$['paths']['/api/me/courses']['post']").exists())
                .andExpect(jsonPath("$['paths']['/api/me/courses']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/me/courses/{id}']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/me/courses/{id}']['delete']").exists())
                .andExpect(jsonPath("$['paths']['/api/me']['get']").exists())
                .andExpect(jsonPath("$['paths']['/api/me']['patch']").exists())
                .andExpect(jsonPath("$['paths']['/api/me/agreements']['patch']").exists())
                .andExpect(jsonPath(
                                "$['paths']['/api/contests/daily-counts']['get']['parameters']"
                                        + "[?(@.name == 'year' && @.required == true)]")
                        .isNotEmpty())
                .andExpect(jsonPath(
                                "$['paths']['/api/contests/daily-counts']['get']['parameters']"
                                        + "[?(@.name == 'month' && @.required == true)]")
                        .isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.ContestDetailResponse.properties.active")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ContestDetailResponse.properties.lat")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ContestDetailResponse.properties.lng")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.ContestDetailResponse.properties.dDay")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.SaveItineraryRequest").exists())
                .andExpect(jsonPath("$.components.schemas.ItineraryDetailResponse").exists())
                .andExpect(jsonPath("$.components.schemas.ItineraryBlockResponse").exists())
                .andExpect(jsonPath("$.components.schemas.ItineraryBlockCreatedResponse").exists())
                .andExpect(jsonPath("$.components.schemas.FavoriteListResponse").exists())
                .andExpect(jsonPath("$.components.schemas.SaveSavedCourseRequest").exists())
                .andExpect(jsonPath("$.components.schemas.SavedCourseListResponse").exists())
                .andExpect(jsonPath("$.components.schemas.SavedCourseDetailResponse").exists())
                .andExpect(jsonPath("$.components.schemas.MemberProfileResponse").exists())
                .andExpect(jsonPath("$.components.schemas.AuthUserResponse.properties.email.type"
                                + "[?(@ == 'null')]")
                        .isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.KakaoProfileResponse.properties.nickname.type"
                                + "[?(@ == 'null')]")
                        .isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.KakaoProfileResponse.properties.email.type"
                                + "[?(@ == 'null')]")
                        .isNotEmpty())
                .andExpect(jsonPath(
                                "$.components.schemas.MemberProfileResponse.properties.email.type"
                                        + "[?(@ == 'null')]")
                        .isNotEmpty())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type")
                        .value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme")
                        .value("bearer"));
    }

    @Test
    void Swagger_UI_진입점을_공개한다() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
    }
}
