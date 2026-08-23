package com.runninggu.server;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CourseApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 게스트에게_번들_코스를_거리와_ID순으로_기본_페이지에_반환한다() throws Exception {
        mockMvc.perform(get("/api/courses"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content", hasSize(4)))
                .andExpect(jsonPath("$.content[0].courseId").value("C001"))
                .andExpect(jsonPath("$.content[1].courseId").value("C004"))
                .andExpect(jsonPath("$.content[2].courseId").value("C003"))
                .andExpect(jsonPath("$.content[3].courseId").value("C002"))
                .andExpect(jsonPath("$.content[0].durationMin").value(27))
                .andExpect(jsonPath("$.content[0].dataSource").value("API_GPX"))
                .andExpect(jsonPath("$.content[0].syncedAt").value(nullValue()))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath(
                        "$.attributions",
                        contains("두루누비 걷기길(한국관광공사)", "테스트 트레일 원천")));
    }

    @Test
    void 지역은_NFC와_앞뒤공백을_정규화하고_sido와_정확히_일치시킨다() throws Exception {
        mockMvc.perform(get("/api/courses").param("region", "  서울  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].courseId").value("C001"))
                .andExpect(jsonPath("$.content[1].courseId").value("C004"))
                .andExpect(jsonPath("$.content[2].courseId").value("C003"))
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }

    @Test
    void 마지막을_넘은_페이지는_빈_content와_빈_출처로_200을_반환한다() throws Exception {
        mockMvc.perform(get("/api/courses")
                        .param("page", "10")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page.number").value(10))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.attributions", hasSize(0)));
    }

    @Test
    void 지역_집계는_건수_내림차순이며_합계가_전체_코스수와_같다() throws Exception {
        mockMvc.perform(get("/api/courses/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].region").value("서울"))
                .andExpect(jsonPath("$.items[0].count").value(3))
                .andExpect(jsonPath("$.items[1].region").value("부산"))
                .andExpect(jsonPath("$.items[1].count").value(1));
    }

    @Test
    void page와_size_범위를_벗어나면_VALIDATION_FAILED다() throws Exception {
        assertValidationFailed("page", "-1");
        assertValidationFailed("size", "0");
        assertValidationFailed("size", "51");
    }

    private void assertValidationFailed(String name, String value) throws Exception {
        mockMvc.perform(get("/api/courses").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.instance").value("/api/courses"));
    }
}
