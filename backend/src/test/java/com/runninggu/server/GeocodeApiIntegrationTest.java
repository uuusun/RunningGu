package com.runninggu.server;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.runninggu.server.common.config.CacheConfig;
import com.runninggu.server.geocode.application.GeocodeProvider;
import com.runninggu.server.geocode.application.GeocodeProviderException;
import com.runninggu.server.geocode.application.GeocodeProviderException.Reason;
import com.runninggu.server.geocode.domain.GeocodeResult;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GeocodeApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private GeocodeProvider provider;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache(CacheConfig.GEOCODE_CACHE).clear();
    }

    @Test
    void 공개_API가_첫_검색결과를_계약대로_반환하고_성공을_캐시한다() throws Exception {
        given(provider.findFirst("해운대해수욕장")).willReturn(Optional.of(new GeocodeResult(
                "해운대해수욕장",
                "부산 해운대구 해운대해변로 264",
                new BigDecimal("35.1587"),
                new BigDecimal("129.1587"))));

        mockMvc.perform(get("/api/geocode").param("query", "해운대해수욕장"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value("해운대해수욕장"))
                .andExpect(jsonPath("$.address").value("부산 해운대구 해운대해변로 264"))
                .andExpect(jsonPath("$.lat").value(35.1587))
                .andExpect(jsonPath("$.lng").value(129.1587));

        mockMvc.perform(get("/api/geocode").param("query", "  해운대해수욕장  "))
                .andExpect(status().isOk());

        verify(provider, times(1)).findFirst("해운대해수욕장");
    }

    @Test
    void query_누락과_공백은_VALIDATION_FAILED다() throws Exception {
        assertProblem(get("/api/geocode"), 400, "VALIDATION_FAILED");
        assertProblem(get("/api/geocode").param("query", "   "), 400, "VALIDATION_FAILED");
    }

    @Test
    void 정상_빈_결과는_NO_RESULT다() throws Exception {
        given(provider.findFirst("없는 장소")).willReturn(Optional.empty());

        assertProblem(
                get("/api/geocode").param("query", "없는 장소"),
                404,
                "NO_RESULT");
    }

    @Test
    void 외부오류와_타임아웃을_502와_504로_구분한다() throws Exception {
        given(provider.findFirst("외부 오류"))
                .willThrow(new GeocodeProviderException(Reason.ERROR));
        given(provider.findFirst("타임아웃"))
                .willThrow(new GeocodeProviderException(Reason.TIMEOUT));

        assertProblem(
                get("/api/geocode").param("query", "외부 오류"),
                502,
                "EXTERNAL_API_ERROR");
        assertProblem(
                get("/api/geocode").param("query", "타임아웃"),
                504,
                "EXTERNAL_API_TIMEOUT");
    }

    @Test
    void OpenAPI에_필수_query와_응답_스키마를_공개한다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/geocode']['get']").exists())
                .andExpect(jsonPath(
                                "$['paths']['/api/geocode']['get']['parameters']"
                                        + "[?(@.name == 'query' && @.required == true)]")
                        .isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.GeocodeResponse.properties.name")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.GeocodeResponse.properties.address")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.GeocodeResponse.properties.lat")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.GeocodeResponse.properties.lng")
                        .exists());
    }

    private void assertProblem(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            int expectedStatus,
            String expectedCode) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.instance").value("/api/geocode"))
                .andExpect(jsonPath("$.traceId").isString());
    }
}
