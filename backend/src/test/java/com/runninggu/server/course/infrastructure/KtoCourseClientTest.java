package com.runninggu.server.course.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.course.application.CourseMetadataBatch;
import com.runninggu.server.course.application.CourseMetadataSyncException;
import com.runninggu.server.course.application.CourseMetadataSyncException.Reason;
import com.runninggu.server.course.domain.CourseDifficulty;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
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
                "decoded+/=key");
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
                "key");
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
                " ");

        assertThatThrownBy(missingKeyClient::fetchAll)
                .isInstanceOfSatisfying(
                        CourseMetadataSyncException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.MISSING_KEY));
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
}
