package com.runninggu.server;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
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
@ExtendWith(OutputCaptureExtension.class)
class ProblemDetailIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailIntegrationTest.class);
    private static final String PRIVATE_EMAIL = "log-privacy-user@example.test";
    private static final String PRIVATE_LATITUDE = "37.5665001";
    private static final String PRIVATE_LONGITUDE = "126.9780001";
    private static final String PRIVATE_TOKEN = "log-privacy-token-value";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Test
    void framework_요청원문_로그와_Tomcat_접속로그를_비활성화한다() {
        assertThat(environment.getProperty("spring.http.log-request-details", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("spring.mvc.log-resolved-exception", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("server.tomcat.accesslog.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("logging.exception-conversion-word"))
                .isEqualTo("%nopex");
        assertThat(environment.getProperty(
                        "logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper"))
                .isEqualTo("OFF");
    }

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
    void 처리되지_않은_오류에서_응답과_로그의_개인정보를_숨긴다(CapturedOutput output)
            throws Exception {
        mockMvc.perform(get("/api/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/errors/internal-server-error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(content().string(not(containsString(PRIVATE_EMAIL))))
                .andExpect(content().string(not(containsString(PRIVATE_LATITUDE))))
                .andExpect(content().string(not(containsString(PRIVATE_LONGITUDE))))
                .andExpect(content().string(not(containsString(PRIVATE_TOKEN))));

        assertThat(output.getAll())
                .doesNotContain(PRIVATE_EMAIL)
                .doesNotContain(PRIVATE_LATITUDE)
                .doesNotContain(PRIVATE_LONGITUDE)
                .doesNotContain(PRIVATE_TOKEN)
                .contains("code=INTERNAL_SERVER_ERROR")
                .contains("exceptionType=java.lang.IllegalStateException")
                .contains("traceId=");
    }

    @Test
    void framework_Throwable_출력에서도_예외_원문을_숨긴다(CapturedOutput output) {
        log.error(
                "framework throwable privacy test",
                new IllegalStateException(
                        "email=" + PRIVATE_EMAIL + " lat=" + PRIVATE_LATITUDE,
                        new IllegalArgumentException("token=" + PRIVATE_TOKEN)));

        assertThat(output.getAll())
                .contains("framework throwable privacy test")
                .doesNotContain(PRIVATE_EMAIL)
                .doesNotContain(PRIVATE_LATITUDE)
                .doesNotContain(PRIVATE_TOKEN);
    }

    @RestController
    @RequestMapping("/api/test")
    public static class ValidationTestController {

        @PostMapping("/validation")
        void validate(@Valid @RequestBody TestRequest request) {}

        @GetMapping("/unexpected")
        void unexpected() {
            IllegalArgumentException cause = new IllegalArgumentException(
                    "email=" + PRIVATE_EMAIL + " token=" + PRIVATE_TOKEN);
            throw new IllegalStateException(
                    "lat=" + PRIVATE_LATITUDE + " lng=" + PRIVATE_LONGITUDE,
                    cause);
        }
    }

    public record TestRequest(@NotBlank(message = "이름은 필수입니다.") String name) {}
}
