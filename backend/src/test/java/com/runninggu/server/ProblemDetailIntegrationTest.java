package com.runninggu.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.runninggu.server.common.error.GlobalExceptionHandler;
import com.runninggu.server.common.error.ProblemDetailFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ProblemDetailIntegrationTest.ValidationTestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
    GlobalExceptionHandler.class,
    ProblemDetailFactory.class,
    ProblemDetailIntegrationTest.ValidationTestController.class
})
class ProblemDetailIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 검증_실패를_RFC9457_형식으로_응답한다() throws Exception {
        mockMvc.perform(post("/api/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/errors/validation-failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.instance").value("/api/test/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].reason").value("이름은 필수입니다."));
    }

    @Test
    void 처리되지_않은_오류에서_내부_메시지를_숨긴다() throws Exception {
        mockMvc.perform(get("/api/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/errors/internal-server-error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(content().string(not(containsString("노출되면 안 되는 내부 메시지"))));
    }

    @RestController
    @RequestMapping("/api/test")
    public static class ValidationTestController {

        @PostMapping("/validation")
        void validate(@Valid @RequestBody TestRequest request) {}

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("노출되면 안 되는 내부 메시지");
        }
    }

    public record TestRequest(@NotBlank(message = "이름은 필수입니다.") String name) {}
}
