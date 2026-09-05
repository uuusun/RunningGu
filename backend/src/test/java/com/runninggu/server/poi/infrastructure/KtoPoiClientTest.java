package com.runninggu.server.poi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.common.upstream.UpstreamLoadGuard;
import com.runninggu.server.common.upstream.UpstreamLoadGuardException;
import com.runninggu.server.common.upstream.UpstreamLoadGuardInterceptor;
import com.runninggu.server.common.upstream.UpstreamLoadGuardProperties;
import com.runninggu.server.common.upstream.UpstreamLoadGuardProperties.EndpointLimits;
import com.runninggu.server.common.upstream.UpstreamProvider;
import com.runninggu.server.poi.application.PoiSearchCriteria;
import com.runninggu.server.poi.application.PoiSourceException;
import com.runninggu.server.poi.application.PoiSourceException.Reason;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import com.runninggu.server.poi.domain.PoiProvider;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class KtoPoiClientTest {

    private MockRestServiceServer korServer;
    private MockRestServiceServer wellnessServer;
    private KtoPoiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder korBuilder = RestClient.builder();
        RestClient.Builder wellnessBuilder = RestClient.builder();
        korServer = MockRestServiceServer.bindTo(korBuilder).build();
        wellnessServer = MockRestServiceServer.bindTo(wellnessBuilder).build();
        client = new KtoPoiClient(
                korBuilder.baseUrl("https://apis.data.test/B551011/KorService2").build(),
                wellnessBuilder
                        .baseUrl("https://apis.data.test/B551011/WellnessTursmService")
                        .build(),
                new ObjectMapper(),
                "decoded+/=key",
                disabledGuard());
    }

    @Test
    void KorService2_위치기반_요청과_소문자_좌표_응답을_변환한다() {
        korServer.expect(request -> {
                    var uri = request.getURI();
                    var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
                    assertThat(uri.getPath())
                            .isEqualTo("/B551011/KorService2/locationBasedList2");
                    assertThat(URLDecoder.decode(
                                    params.getFirst("serviceKey"),
                                    StandardCharsets.UTF_8))
                            .isEqualTo("decoded+/=key");
                    assertThat(params.getFirst("MobileOS")).isEqualTo("ETC");
                    assertThat(params.getFirst("MobileApp")).isEqualTo("runninggu");
                    assertThat(params.getFirst("_type")).isEqualTo("json");
                    assertThat(params.getFirst("mapX")).isEqualTo("127.2714");
                    assertThat(params.getFirst("mapY")).isEqualTo("36.4912");
                    assertThat(params.getFirst("radius")).isEqualTo("8000");
                    assertThat(params.getFirst("arrange")).isEqualTo("E");
                    assertThat(params.getFirst("contentTypeId")).isEqualTo("32");
                })
                .andRespond(withSuccess(
                        successBody("""
                                [{
                                  "contentid":"1","title":"호텔 세종 가온",
                                  "mapx":"127.2714","mapy":"36.4912","dist":"1200.4",
                                  "addr1":"세종특별자치시 어진동","addr2":"123",
                                  "firstimage":"https://example.test/hotel.jpg"
                                }]
                                """, 1),
                        MediaType.APPLICATION_JSON));

        List<Poi> result = client.search(criteria(PoiCategory.LODGING, ""), 8);

        assertThat(result).singleElement().satisfies(poi -> {
            assertThat(poi.name()).isEqualTo("호텔 세종 가온");
            assertThat(poi.category()).isEqualTo(PoiCategory.LODGING);
            assertThat(poi.provider()).isEqualTo(PoiProvider.KTO);
            assertThat(poi.lat()).isEqualByComparingTo("36.4912");
            assertThat(poi.lng()).isEqualByComparingTo("127.2714");
            assertThat(poi.distanceM()).isEqualTo(1200);
            assertThat(poi.address()).isEqualTo("세종특별자치시 어진동 123");
            assertThat(poi.url()).isEmpty();
            assertThat(poi.imageUrl()).isEqualTo("https://example.test/hotel.jpg");
        });
        korServer.verify();
    }

    @Test
    void 웰니스는_전용_locationBasedList와_KOR_대문자_좌표를_사용한다() {
        wellnessServer.expect(request -> {
                    var uri = request.getURI();
                    var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
                    assertThat(uri.getPath())
                            .isEqualTo("/B551011/WellnessTursmService/locationBasedList");
                    assertThat(params.getFirst("langDivCd")).isEqualTo("KOR");
                    assertThat(params).doesNotContainKey("contentTypeId");
                })
                .andRespond(withSuccess(
                        successBody("""
                                {"title":"세종 웰니스","mapX":"127.27","mapY":"36.49",
                                "dist":"100","wellnessThemaCd":"EX050100","addr1":"세종"}
                                """, 1),
                        MediaType.APPLICATION_JSON));

        assertThat(client.search(criteria(PoiCategory.WELLNESS, ""), 8))
                .singleElement()
                .satisfies(poi -> {
                    assertThat(poi.provider()).isEqualTo(PoiProvider.KTO);
                    assertThat(poi.description()).isEqualTo("EX050100");
                });
        wellnessServer.verify();
    }

    @Test
    void query는_KTO_위치결과의_이름을_공백_무시해_필터한다() {
        korServer.expect(request -> {})
                .andRespond(withSuccess(
                        successBody("""
                                [{"title":"세종 가온 호텔","mapx":"127.27","mapy":"36.49","dist":"1"},
                                 {"title":"다른 숙소","mapx":"127.28","mapy":"36.50","dist":"2"}]
                                """, 2),
                        MediaType.APPLICATION_JSON));

        assertThat(client.search(criteria(PoiCategory.LODGING, "가온호텔"), 8))
                .extracting(Poi::name)
                .containsExactly("세종 가온 호텔");
        korServer.verify();
    }

    @Test
    void 정상_0건은_빈_목록이다() {
        korServer.expect(request -> {})
                .andRespond(withSuccess(
                        """
                        {"response":{"header":{"resultCode":"0000"},"body":{
                          "items":"","numOfRows":100,"pageNo":1,"totalCount":0
                        }}}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThat(client.search(criteria(PoiCategory.TOUR, ""), 8)).isEmpty();
        korServer.verify();
    }

    @Test
    void 실패코드는_외부오류다() {
        korServer.expect(request -> {})
                .andRespond(withSuccess(
                        """
                        {"response":{"header":{"resultCode":"30","resultMsg":"KEY ERROR"}}}
                        """,
                        MediaType.APPLICATION_JSON));
        assertReason(Reason.ERROR);
        korServer.verify();
    }

    @Test
    void guard가_켜지면_누락된_KTO_실패코드는_전체_시험을_trip한다() {
        UpstreamLoadGuard guard = enabledGuard();
        RestClient.Builder korBuilder = RestClient.builder();
        RestClient.Builder wellnessBuilder = RestClient.builder();
        MockRestServiceServer guardedKorServer = MockRestServiceServer.bindTo(korBuilder).build();
        MockRestServiceServer guardedWellnessServer = MockRestServiceServer
                .bindTo(wellnessBuilder)
                .build();
        UpstreamLoadGuardInterceptor interceptor = new UpstreamLoadGuardInterceptor(
                guard,
                UpstreamProvider.KTO);
        KtoPoiClient guardedClient = new KtoPoiClient(
                korBuilder
                        .baseUrl("https://apis.data.go.kr/B551011/KorService2")
                        .requestInterceptor(interceptor)
                        .build(),
                wellnessBuilder
                        .baseUrl("https://apis.data.go.kr/B551011/WellnessTursmService")
                        .requestInterceptor(interceptor)
                        .build(),
                new ObjectMapper(),
                "key",
                guard);
        guardedKorServer.expect(request -> {})
                .andRespond(withSuccess(
                        """
                        {"response":{"header":{},"body":{"items":"","totalCount":0}}}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> guardedClient.search(criteria(PoiCategory.TOUR, ""), 8))
                .isInstanceOf(UpstreamLoadGuardException.class);
        guardedKorServer.verify();
        guardedWellnessServer.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "[]"})
    void guard가_켜지면_resultCode를_판독할_수_없는_본문도_trip한다(String responseBody) {
        UpstreamLoadGuard guard = enabledGuard();
        RestClient.Builder korBuilder = RestClient.builder();
        RestClient.Builder wellnessBuilder = RestClient.builder();
        MockRestServiceServer guardedKorServer = MockRestServiceServer.bindTo(korBuilder).build();
        MockRestServiceServer guardedWellnessServer = MockRestServiceServer
                .bindTo(wellnessBuilder)
                .build();
        UpstreamLoadGuardInterceptor interceptor = new UpstreamLoadGuardInterceptor(
                guard,
                UpstreamProvider.KTO);
        KtoPoiClient guardedClient = new KtoPoiClient(
                korBuilder
                        .baseUrl("https://apis.data.go.kr/B551011/KorService2")
                        .requestInterceptor(interceptor)
                        .build(),
                wellnessBuilder
                        .baseUrl("https://apis.data.go.kr/B551011/WellnessTursmService")
                        .requestInterceptor(interceptor)
                        .build(),
                new ObjectMapper(),
                "key",
                guard);
        guardedKorServer.expect(request -> {})
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> guardedClient.search(criteria(PoiCategory.TOUR, ""), 8))
                .isInstanceOfSatisfying(
                        UpstreamLoadGuardException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(UpstreamLoadGuardException.Reason.KTO_RESULT_CODE));
        guardedKorServer.verify();
        guardedWellnessServer.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "[]"})
    void guard가_꺼지면_resultCode를_판독할_수_없는_본문은_기존_외부오류다(String responseBody) {
        korServer.expect(request -> {})
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        assertReason(Reason.ERROR);
        korServer.verify();
        wellnessServer.verify();
    }

    @Test
    void JSON을_요청했는데_XML이_오면_외부오류다() {
        korServer.expect(request -> {})
                .andRespond(withSuccess("<OpenAPI_ServiceResponse/>", MediaType.APPLICATION_XML));
        assertReason(Reason.ERROR);
        korServer.verify();
    }

    @Test
    void 타임아웃은_별도_실패_종류로_전달한다() {
        korServer.expect(request -> {})
                .andRespond(withException(new SocketTimeoutException("timeout")));

        assertReason(Reason.TIMEOUT);
        korServer.verify();
    }

    private PoiSearchCriteria criteria(PoiCategory category, String query) {
        return new PoiSearchCriteria(
                category,
                new BigDecimal("36.4912"),
                new BigDecimal("127.2714"),
                8_000,
                query,
                8);
    }

    private void assertReason(Reason expected) {
        assertThatThrownBy(() -> client.search(criteria(PoiCategory.TOUR, ""), 8))
                .isInstanceOfSatisfying(
                        PoiSourceException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(expected));
    }

    private String successBody(String items, int totalCount) {
        return """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{
                  "items":{"item":%s},"numOfRows":100,"pageNo":1,"totalCount":%d
                }}}
                """.formatted(items, totalCount);
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
                "poi-test",
                100,
                new EndpointLimits(100, 100, 100, 100, 100, 100, 100, 100)));
    }
}
