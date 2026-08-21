package com.runninggu.server.festival.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.festival.application.FestivalProviderException;
import com.runninggu.server.festival.application.FestivalProviderException.Reason;
import com.runninggu.server.festival.domain.Festival;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class KtoFestivalClientTest {

    private static final LocalDate EVENT_START_DATE = LocalDate.of(2026, 8, 7);

    private MockRestServiceServer server;
    private KtoFestivalClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KtoFestivalClient(
                builder.baseUrl("https://apis.data.test/B551011/KorService2").build(),
                new ObjectMapper(),
                "decoded+/=key");
    }

    @Test
    void searchFestival2_공통_parameter와_디코딩키를_전달하고_응답을_변환한다() {
        server.expect(request -> {
                    var uri = request.getURI();
                    var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
                    assertThat(uri.getPath())
                            .isEqualTo("/B551011/KorService2/searchFestival2");
                    assertThat(URLDecoder.decode(
                                    params.getFirst("serviceKey"),
                                    StandardCharsets.UTF_8))
                            .isEqualTo("decoded+/=key");
                    assertThat(params.getFirst("numOfRows")).isEqualTo("1000");
                    assertThat(params.getFirst("pageNo")).isEqualTo("1");
                    assertThat(params.getFirst("MobileOS")).isEqualTo("ETC");
                    assertThat(params.getFirst("MobileApp")).isEqualTo("runninggu");
                    assertThat(params.getFirst("_type")).isEqualTo("json");
                    assertThat(params.getFirst("eventStartDate")).isEqualTo("20260807");
                })
                .andRespond(withSuccess(
                        successBody(
                                2,
                                """
                                [{
                                  "contentid":"2764321",
                                  "title":"세종 빛 축제",
                                  "eventstartdate":"20260820",
                                  "eventenddate":"20260825",
                                  "mapx":"127.2714",
                                  "mapy":"36.4912",
                                  "firstimage":"",
                                  "addr1":""
                                },{
                                  "contentid":"invalid",
                                  "title":"좌표 없는 축제",
                                  "eventstartdate":"20260820",
                                  "eventenddate":"20260825",
                                  "mapx":"",
                                  "mapy":""
                                }]
                                """),
                        MediaType.APPLICATION_JSON));

        List<Festival> result = client.searchStartingFrom(EVENT_START_DATE);

        assertThat(result).singleElement().satisfies(festival -> {
            assertThat(festival.contentId()).isEqualTo("2764321");
            assertThat(festival.name()).isEqualTo("세종 빛 축제");
            assertThat(festival.startDate()).isEqualTo("2026-08-20");
            assertThat(festival.endDate()).isEqualTo("2026-08-25");
            assertThat(festival.lat()).isEqualByComparingTo("36.4912");
            assertThat(festival.lng()).isEqualByComparingTo("127.2714");
            assertThat(festival.imageUrl()).isNull();
            assertThat(festival.address()).isEmpty();
        });
        server.verify();
    }

    @Test
    void totalCount가_페이지크기보다_크면_다음_페이지까지_조회한다() {
        server.expect(request -> assertThat(UriComponentsBuilder
                                .fromUri(request.getURI())
                                .build()
                                .getQueryParams()
                                .getFirst("pageNo"))
                        .isEqualTo("1"))
                .andRespond(withSuccess(
                        successBody(1001, item("first", "127.0", "37.0")),
                        MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(UriComponentsBuilder
                                .fromUri(request.getURI())
                                .build()
                                .getQueryParams()
                                .getFirst("pageNo"))
                        .isEqualTo("2"))
                .andRespond(withSuccess(
                        successBody(1001, item("second", "127.1", "37.1")),
                        MediaType.APPLICATION_JSON));

        assertThat(client.searchStartingFrom(EVENT_START_DATE))
                .extracting(Festival::contentId)
                .containsExactly("first", "second");
        server.verify();
    }

    @Test
    void totalCount_0과_문자열_items는_정상_빈_결과다() {
        server.expect(request -> {})
                .andRespond(withSuccess(
                        """
                        {"response":{"header":{"resultCode":"0000"},"body":{
                          "items":"","numOfRows":1000,"pageNo":1,"totalCount":0
                        }}}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThat(client.searchStartingFrom(EVENT_START_DATE)).isEmpty();
        server.verify();
    }

    @Test
    void KTO_실패코드는_외부오류다() {
        server.expect(request -> {})
                .andRespond(withSuccess(
                        """
                        {"response":{"header":{"resultCode":"30","resultMsg":"SERVICE KEY ERROR"}}}
                        """,
                        MediaType.APPLICATION_JSON));

        assertReason(Reason.ERROR);
        server.verify();
    }

    @Test
    void JSON을_요청했는데_XML이_오면_외부오류다() {
        server.expect(request -> {})
                .andRespond(withSuccess(
                        "<OpenAPI_ServiceResponse><cmmMsgHeader/></OpenAPI_ServiceResponse>",
                        MediaType.APPLICATION_XML));

        assertReason(Reason.ERROR);
        server.verify();
    }

    @Test
    void HTTP_오류는_외부오류다() {
        server.expect(request -> {})
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertReason(Reason.ERROR);
        server.verify();
    }

    @Test
    void 타임아웃은_별도_실패_종류로_전달한다() {
        server.expect(request -> {})
                .andRespond(withException(new SocketTimeoutException("timeout")));

        assertReason(Reason.TIMEOUT);
        server.verify();
    }

    @Test
    void 서버키가_없으면_외부호출하지_않고_실패한다() {
        KtoFestivalClient missingKeyClient = new KtoFestivalClient(
                RestClient.create("https://apis.data.test"),
                new ObjectMapper(),
                " ");

        assertThatThrownBy(() -> missingKeyClient.searchStartingFrom(EVENT_START_DATE))
                .isInstanceOfSatisfying(
                        FestivalProviderException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.ERROR));
    }

    private void assertReason(Reason expected) {
        assertThatThrownBy(() -> client.searchStartingFrom(EVENT_START_DATE))
                .isInstanceOfSatisfying(
                        FestivalProviderException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(expected));
    }

    private String successBody(int totalCount, String items) {
        return """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{
                  "items":{"item":%s},"numOfRows":1000,"pageNo":1,"totalCount":%d
                }}}
                """.formatted(items, totalCount);
    }

    private String item(String contentId, String lng, String lat) {
        return """
                [{
                  "contentid":"%s",
                  "title":"축제 %s",
                  "eventstartdate":"20260820",
                  "eventenddate":"20260825",
                  "mapx":"%s",
                  "mapy":"%s",
                  "firstimage":"https://example.test/%s.jpg",
                  "addr1":"주소"
                }]
                """.formatted(contentId, contentId, lng, lat, contentId);
    }
}
