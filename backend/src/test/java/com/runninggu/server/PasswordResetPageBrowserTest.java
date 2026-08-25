package com.runninggu.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.runninggu.server.auth.application.PasswordPolicyCases;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasswordResetPageBrowserTest extends PostgreSqlContainerSupport {

    private static final String RESET_TOKEN = "browser-test-token";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    @BeforeAll
    void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    void closeBrowser() {
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void createPage() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        context.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.runninggu.server.auth.application.PasswordPolicyCases#invalidCases")
    void 잘못된_비밀번호는_API를_호출하지_않고_입력_오류를_표시한다(
            PasswordPolicyCases.PasswordCase passwordCase) {
        AtomicInteger requestCount = new AtomicInteger();
        routeResetApi(route -> {
            requestCount.incrementAndGet();
            route.fulfill(new Route.FulfillOptions().setStatus(204));
        });
        openResetPage();

        page.locator("#new-password").fill(passwordCase.password());
        page.locator("#confirm-password").fill(passwordCase.password());
        page.locator("#submit-button").click();

        PlaywrightAssertions.assertThat(page.locator("#new-password"))
                .hasAttribute("aria-invalid", "true");
        PlaywrightAssertions.assertThat(page.locator("#message"))
                .hasText(passwordCase.browserMessage());
        assertEquals("new-password", page.evaluate("document.activeElement.id"));
        assertEquals(0, requestCount.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.runninggu.server.auth.application.PasswordPolicyCases#validCases")
    void 올바른_비밀번호는_token과_함께_API로_전송한다(
            PasswordPolicyCases.PasswordCase passwordCase) throws JsonProcessingException {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        routeResetApi(route -> {
            requestCount.incrementAndGet();
            requestBody.set(route.request().postData());
            route.fulfill(new Route.FulfillOptions().setStatus(204));
        });
        openResetPage();

        page.locator("#new-password").fill(passwordCase.password());
        page.locator("#confirm-password").fill(passwordCase.password());
        page.locator("#submit-button").click();

        PlaywrightAssertions.assertThat(page.locator("#message"))
                .hasText("비밀번호를 변경했습니다. 런닝구 앱에서 다시 로그인해 주세요.");
        assertEquals(1, requestCount.get());
        assertNotNull(requestBody.get());
        JsonNode payload = objectMapper.readTree(requestBody.get());
        assertEquals(RESET_TOKEN, payload.path("token").asText());
        assertEquals(passwordCase.password(), payload.path("newPassword").asText());
    }

    @Test
    void 비밀번호_확인이_다르면_API를_호출하지_않는다() {
        AtomicInteger requestCount = new AtomicInteger();
        routeResetApi(route -> {
            requestCount.incrementAndGet();
            route.fulfill(new Route.FulfillOptions().setStatus(204));
        });
        openResetPage();

        page.locator("#new-password").fill("run4life");
        page.locator("#confirm-password").fill("different4");
        page.locator("#submit-button").click();

        PlaywrightAssertions.assertThat(page.locator("#confirm-password"))
                .hasAttribute("aria-invalid", "true");
        PlaywrightAssertions.assertThat(page.locator("#message"))
                .hasText("새 비밀번호가 서로 일치하지 않습니다.");
        assertEquals("confirm-password", page.evaluate("document.activeElement.id"));
        assertEquals(0, requestCount.get());
    }

    @Test
    void 입력을_고치면_해당_필드의_오류만_지운다() {
        routeResetApi(route -> route.fulfill(new Route.FulfillOptions().setStatus(204)));
        openResetPage();

        page.locator("#new-password").fill("short1");
        page.locator("#confirm-password").fill("short1");
        page.locator("#submit-button").click();
        page.locator("#new-password").fill("short12");

        PlaywrightAssertions.assertThat(page.locator("#new-password"))
                .not().hasAttribute("aria-invalid", "true");
        PlaywrightAssertions.assertThat(page.locator("#message")).isEmpty();
    }

    @Test
    void 서버의_INVALID_PASSWORD_detail을_필드_오류로_표시한다() {
        routeResetApi(route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(400)
                .setContentType("application/problem+json")
                .setBody("""
                        {"code":"INVALID_PASSWORD","detail":"서버 비밀번호 정책 안내"}
                        """)));
        openResetPage();

        page.locator("#new-password").fill("run4life");
        page.locator("#confirm-password").fill("run4life");
        page.locator("#submit-button").click();

        PlaywrightAssertions.assertThat(page.locator("#new-password"))
                .hasAttribute("aria-invalid", "true");
        PlaywrightAssertions.assertThat(page.locator("#message"))
                .hasText("서버 비밀번호 정책 안내");
    }

    @Test
    void 비밀번호_설명과_바이트_상한을_입력에_연결한다() {
        openResetPage();

        PlaywrightAssertions.assertThat(page.locator("#new-password"))
                .hasAttribute(
                        "aria-describedby",
                        "password-description password-hint");
    }

    private void openResetPage() {
        page.navigate("http://127.0.0.1:" + port + "/reset-password?token=" + RESET_TOKEN);
    }

    private void routeResetApi(java.util.function.Consumer<Route> handler) {
        page.route("**/api/auth/password/reset", handler::accept);
    }
}
