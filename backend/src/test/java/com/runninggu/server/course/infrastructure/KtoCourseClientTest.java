package com.runninggu.server.course.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.common.upstream.UpstreamLoadGuard;
import com.runninggu.server.common.upstream.UpstreamLoadGuardException;
import com.runninggu.server.common.upstream.UpstreamLoadGuardInterceptor;
import com.runninggu.server.common.upstream.UpstreamLoadGuardProperties;
import com.runninggu.server.common.upstream.UpstreamLoadGuardProperties.EndpointLimits;
import com.runninggu.server.common.upstream.UpstreamProvider;
import com.runninggu.server.course.application.CourseMetadataBatch;
import com.runninggu.server.course.application.CourseMetadataSyncException;
import com.runninggu.server.course.application.CourseMetadataSyncException.Reason;
import com.runninggu.server.course.domain.CourseDifficulty;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class KtoCourseClientTest {

    private MockRestServiceServer server;
    private KtoCourseClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KtoCourseClient(
                builder.baseUrl("https://apis.data.test/B551011/Durunubi").build(),
                new ObjectMapper(),
                "decoded+/=key",
                disabledGuard());
    }

    @Test
    void courseList_계약_parameter로_전체_페이지를_받고_유효한_최신_메타를_정규화한다() {
        server.expect(request -> {
                    var uri = request.getURI();
                    var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
                    assertThat(uri.getPath()).isEqualTo("/B551011/Durunubi/courseList");
                    assertThat(URLDecoder.decode(params.getFirst("serviceKey"), StandardCharsets.UTF_8))
                            .isEqualTo("decoded+/=key");
                    assertThat(params.getFirst("pageNo")).isEqualTo("1");
                    assertThat(params.getFirst("numOfRows")).isEqualTo("100");
                    assertThat(params.getFirst("MobileOS")).isEqualTo("ETC");
                    assertThat(params.getFirst("MobileApp")).isEqualTo("RunningGu");
                    assertThat(params.getFirst("brdDiv")).isEqualTo("DNWW");
                    assertThat(params.getFirst("_type")).isEqualTo("json");
                })
                .andRespond(withSuccess(successBody(2, item(
                        "C1", "  최신  코스  ", "3", " 순환형 ", "<p>최신&nbsp;요약</p>")), MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(UriComponentsBuilder
                                .fromUri(request.getURI())
                                .build()
                                .getQueryParams()
                                .getFirst("pageNo"))
                        .isEqualTo("2"))
                .andRespond(withSuccess(successBody(2, item(
                        "C2", "두 번째", "9", "", "")), MediaType.APPLICATION_JSON));

        CourseMetadataBatch batch = client.fetchAll();

        assertThat(batch.rawCount()).isEqualTo(2);
        assertThat(batch.invalidFieldCount()).isEqualTo(3);
        assertThat(batch.items().get("C1")).satisfies(metadata -> {
            assertThat(metadata.courseName()).isEqualTo("최신 코스");
            assertThat(metadata.difficulty()).isEqualTo(CourseDifficulty.HARD);
            assertThat(metadata.cycle()).isEqualTo("순환형");
            assertThat(metadata.summary()).isEqualTo("최신 요약");
        });
        assertThat(batch.items().get("C2")).satisfies(metadata -> {
            assertThat(metadata.courseName()).isEqualTo("두 번째");
            assertThat(metadata.difficulty()).isNull();
            assertThat(metadata.cycle()).isNull();
            assertThat(metadata.summary()).isNull();
        });
        server.verify();
    }

    @Test
    void 중복_courseId가_있으면_전체_동기화를_실패시킨다() {
        server.expect(request -> {})
                .andRespond(withSuccess(successBody(2, "[" + rawItem("C1") + "," + rawItem("C1") + "]"), MediaType.APPLICATION_JSON));

        assertReason(Reason.INVALID_RESPONSE);
        server.verify();
    }

    @Test
    void 빈_전체응답이나_JSON이_아닌_응답은_전체_동기화를_실패시킨다() {
        server.expect(request -> {})
                .andRespond(withSuccess(successBody(0, "[]"), MediaType.APPLICATION_JSON));
        assertReason(Reason.INVALID_RESPONSE);
        server.verify();

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer xmlServer = MockRestServiceServer.bindTo(builder).build();
        KtoCourseClient xmlClient = new KtoCourseClient(
                builder.baseUrl("https://apis.data.test/B551011/Durunubi").build(),
                new ObjectMapper(),
                "key",
                disabledGuard());
        xmlServer.expect(request -> {})
                .andRespond(withSuccess("<OpenAPI_ServiceResponse/>", MediaType.APPLICATION_XML));

        assertThatThrownBy(xmlClient::fetchAll)
                .isInstanceOfSatisfying(
                        CourseMetadataSyncException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.INVALID_RESPONSE));
        xmlServer.verify();
    }

    @Test
    void 서버키가_없으면_외부호출_없이_실패한다() {
        KtoCourseClient missingKeyClient = new KtoCourseClient(
                RestClient.create("https://apis.data.test"),
                new ObjectMapper(),
                " ",
                disabledGuard());

        assertThatThrownBy(missingKeyClient::fetchAll)
                .isInstanceOfSatisfying(
                        CourseMetadataSyncException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.MISSING_KEY));
    }

    @Test
    void HTTP_오류와_timeout을_구분해_전체_동기화를_실패시킨다() {
        server.expect(request -> {}).andRespond(withServerError());
        assertReason(Reason.HTTP_ERROR);
        server.verify();

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer timeoutServer = MockRestServiceServer.bindTo(builder).build();
        KtoCourseClient timeoutClient = new KtoCourseClient(
                builder.baseUrl("https://apis.data.test/B551011/Durunubi").build(),
                new ObjectMapper(),
                "key",
                disabledGuard());
        timeoutServer.expect(request -> {}).andRespond(request -> {
            throw new ResourceAccessException("timeout", new SocketTimeoutException());
        });

        assertThatThrownBy(timeoutClient::fetchAll)
                .isInstanceOfSatisfying(
                        CourseMetadataSyncException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.TIMEOUT));
        timeoutServer.verify();
    }

    @Test
    void KTO_오류코드와_전체건수_변경은_부분_snapshot으로_받지_않는다() {
        server.expect(request -> {}).andRespond(withSuccess(
                """
                {"response":{"header":{"resultCode":"30","resultMsg":"SERVICE KEY ERROR"}}}
                """,
                MediaType.APPLICATION_JSON));
        assertReason(Reason.INVALID_RESPONSE);
        server.verify();

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer changingTotalServer = MockRestServiceServer.bindTo(builder).build();
        KtoCourseClient changingTotalClient = new KtoCourseClient(
                builder.baseUrl("https://apis.data.test/B551011/Durunubi").build(),
                new ObjectMapper(),
                "key",
                disabledGuard());
        changingTotalServer.expect(request -> {})
                .andRespond(withSuccess(successBody(2, rawItem("C1")), MediaType.APPLICATION_JSON));
        changingTotalServer.expect(request -> {})
                .andRespond(withSuccess(successBody(3, rawItem("C2")), MediaType.APPLICATION_JSON));

        assertThatThrownBy(changingTotalClient::fetchAll)
                .isInstanceOfSatisfying(
                        CourseMetadataSyncException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.INVALID_RESPONSE));
        changingTotalServer.verify();
    }

    @Test
    void guard가_켜지면_KTO_오류코드는_전체_시험을_trip한다() {
        UpstreamLoadGuard guard = enabledGuard();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer guardedServer = MockRestServiceServer.bindTo(builder).build();
        KtoCourseClient guardedClient = new KtoCourseClient(
                builder
                        .baseUrl("https://apis.data.go.kr/B551011/Durunubi")
                        .requestInterceptor(new UpstreamLoadGuardInterceptor(
                                guard,
                                UpstreamProvider.KTO))
                        .build(),
                new ObjectMapper(),
                "key",
                guard);
        guardedServer.expect(request -> {})
                .andRespond(withSuccess(
                        """
                        {"response":{"header":{"resultCode":"30","resultMsg":"KEY ERROR"}}}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(guardedClient::fetchAll)
                .isInstanceOf(UpstreamLoadGuardException.class);
        guardedServer.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "[]"})
    void guard가_켜지면_resultCode를_판독할_수_없는_본문도_trip한다(String responseBody) {
        UpstreamLoadGuard guard = enabledGuard();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer guardedServer = MockRestServiceServer.bindTo(builder).build();
        KtoCourseClient guardedClient = new KtoCourseClient(
                builder
                        .baseUrl("https://apis.data.go.kr/B551011/Durunubi")
                        .requestInterceptor(new UpstreamLoadGuardInterceptor(
                                guard,
                                UpstreamProvider.KTO))
                        .build(),
                new ObjectMapper(),
                "key",
                guard);
        guardedServer.expect(request -> {})
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(guardedClient::fetchAll)
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(UpstreamLoadGuardException.Reason.KTO_RESULT_CODE));
        guardedServer.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "[]"})
    void guard가_꺼지면_resultCode를_판독할_수_없는_본문은_기존_외부오류다(String responseBody) {
        server.expect(request -> {})
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertReason(Reason.INVALID_RESPONSE);
        server.verify();
    }

    private void assertReason(Reason expected) {
        assertThatThrownBy(client::fetchAll)
                .isInstanceOfSatisfying(
                        CourseMetadataSyncException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(expected));
    }

    private String successBody(int totalCount, String items) {
        return """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{
                  "items":{"item":%s},"numOfRows":100,"pageNo":1,"totalCount":%d
                }}}
                """.formatted(items, totalCount);
    }

    private String item(
            String id,
            String name,
            String level,
            String cycle,
            String summary) {
        return """
                [{
                  "crsIdx":"%s",
                  "crsKorNm":"%s",
                  "crsLevel":"%s",
                  "crsCycle":"%s",
                  "crsSummary":"%s"
                }]
                """.formatted(id, name, level, cycle, summary);
    }

    private String rawItem(String id) {
        return """
                {"crsIdx":"%s","crsKorNm":"코스","crsLevel":"1","crsCycle":"순환형","crsSummary":"요약"}
                """.formatted(id);
    }

    private UpstreamLoadGuard disabledGuard() {
        return new UpstreamLoadGuard(new UpstreamLoadGuardProperties(
                false,
                "local",
                null,
                null,
                null));
    }

    private UpstreamLoadGuard enabledGuard() {
        return new UpstreamLoadGuard(new UpstreamLoadGuardProperties(
                true,
                "staging",
                "course-test",
                100,
                new EndpointLimits(100, 100, 100, 100, 100, 100, 100, 100)));
    }
}
