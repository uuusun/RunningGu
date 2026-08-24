package com.runninggu.server.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.runninggu.server.auth.application.KakaoUserInfoException;
import com.runninggu.server.auth.application.KakaoUserInfoException.Reason;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoUserInfoClientTest {

    private static final long EXPECTED_APP_ID = 1234L;

    private MockRestServiceServer server;
    private KakaoUserInfoClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoUserInfoClient(
                builder.baseUrl("https://kapi.kakao.test").build(), EXPECTED_APP_ID);
    }

    @Test
    void 우리_앱이_발급한_Bearer_토큰으로_회원번호와_동의된_프로필을_조회한다() {
        expectTokenInfo("kakao-token", 123456789012345L, EXPECTED_APP_ID);
        server.expect(requestTo("https://kapi.kakao.test/v2/user/me"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer kakao-token"))
                .andRespond(withSuccess(
                        """
                        {
                          "id": 123456789012345,
                          "connected_at": "2026-08-23T00:00:00Z",
                          "kakao_account": {
                            "profile": {"nickname": "  카카오러너  "},
                            "email": "  runner@kakao.com  "
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        var profile = client.retrieve("kakao-token");

        assertThat(profile.subject()).isEqualTo("123456789012345");
        assertThat(profile.nickname()).isEqualTo("카카오러너");
        assertThat(profile.email()).isEqualTo("runner@kakao.com");
        server.verify();
    }

    @Test
    void 동의되지_않은_닉네임과_이메일은_null이다() {
        expectTokenInfo("token", 123L, EXPECTED_APP_ID);
        server.expect(requestTo("https://kapi.kakao.test/v2/user/me"))
                .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));

        var profile = client.retrieve("token");

        assertThat(profile.nickname()).isNull();
        assertThat(profile.email()).isNull();
        server.verify();
    }

    @Test
    void 회원번호가_없는_성공응답은_외부오류다() {
        expectTokenInfo("token", 123L, EXPECTED_APP_ID);
        server.expect(requestTo("https://kapi.kakao.test/v2/user/me"))
                .andRespond(withSuccess(
                        "{\"kakao_account\":{}}",
                        MediaType.APPLICATION_JSON));

        assertReason(() -> client.retrieve("token"), Reason.ERROR);
        server.verify();
    }

    @Test
    void 카카오_401은_무효토큰으로_구분한다() {
        server.expect(requestTo("https://kapi.kakao.test/v1/user/access_token_info"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer expired-token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertReason(() -> client.retrieve("expired-token"), Reason.INVALID_TOKEN);
        server.verify();
    }

    @Test
    void 다른_카카오_앱이_발급한_토큰은_무효토큰으로_거부한다() {
        expectTokenInfo("foreign-token", 123L, 9999L);

        assertReason(() -> client.retrieve("foreign-token"), Reason.INVALID_TOKEN);
        server.verify();
    }

    @Test
    void 토큰_정보에_앱_ID가_없으면_외부오류다() {
        server.expect(requestTo("https://kapi.kakao.test/v1/user/access_token_info"))
                .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));

        assertReason(() -> client.retrieve("token"), Reason.ERROR);
        server.verify();
    }

    @Test
    void 토큰_정보와_사용자_정보의_회원번호가_다르면_외부오류다() {
        expectTokenInfo("token", 123L, EXPECTED_APP_ID);
        server.expect(requestTo("https://kapi.kakao.test/v2/user/me"))
                .andRespond(withSuccess("{\"id\":456}", MediaType.APPLICATION_JSON));

        assertReason(() -> client.retrieve("token"), Reason.ERROR);
        server.verify();
    }

    @Test
    void 카카오_429는_재시도하지_않고_외부오류로_구분한다() {
        server.expect(requestTo("https://kapi.kakao.test/v1/user/access_token_info"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertReason(() -> client.retrieve("rate-limited-token"), Reason.ERROR);
        server.verify();
    }

    @Test
    void 카카오_오류와_타임아웃을_구분한다() {
        server.expect(requestTo("https://kapi.kakao.test/v1/user/access_token_info"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertReason(() -> client.retrieve("server-error"), Reason.ERROR);
        server.verify();

        setUp();
        server.expect(requestTo("https://kapi.kakao.test/v1/user/access_token_info"))
                .andRespond(withException(new SocketTimeoutException("timeout")));
        assertReason(() -> client.retrieve("timeout"), Reason.TIMEOUT);
        server.verify();
    }

    @Test
    void 빈_토큰은_외부호출하지_않고_무효토큰이다() {
        assertReason(() -> client.retrieve("  "), Reason.INVALID_TOKEN);
    }

    private void expectTokenInfo(String accessToken, long userId, long appId) {
        server.expect(requestTo("https://kapi.kakao.test/v1/user/access_token_info"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andRespond(withSuccess(
                        "{\"id\":%d,\"expires_in\":7199,\"app_id\":%d}"
                                .formatted(userId, appId),
                        MediaType.APPLICATION_JSON));
    }

    private void assertReason(ThrowingCall call, Reason expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        KakaoUserInfoException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
