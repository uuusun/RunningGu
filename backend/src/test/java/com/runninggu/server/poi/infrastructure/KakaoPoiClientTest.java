package com.runninggu.server.poi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class KakaoPoiClientTest {

    private MockRestServiceServer server;
    private KakaoPoiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoPoiClient(
                builder.baseUrl("https://dapi.kakao.test").build(),
                "test-rest-key");
    }

    @Test
    void 숙소_query를_AD5_키워드_검색으로_보내고_응답을_변환한다() {
        server.expect(request -> {
                    var uri = request.getURI();
                    var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
                    assertThat(uri.getPath()).isEqualTo("/v2/local/search/keyword.json");
                    assertThat(URLDecoder.decode(
                                    params.getFirst("query"),
                                    StandardCharsets.UTF_8))
                            .isEqualTo("세종 호텔");
                    assertThat(params.getFirst("category_group_code")).isEqualTo("AD5");
                    assertThat(params.getFirst("x")).isEqualTo("127.2714");
                    assertThat(params.getFirst("y")).isEqualTo("36.4912");
                    assertThat(params.getFirst("radius")).isEqualTo("8000");
                    assertThat(params.getFirst("sort")).isEqualTo("distance");
                })
                .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK test-rest-key"))
                .andRespond(withSuccess(
                        response(
                                true,
                                """
                                [{
                                  "place_name":"호텔 세종 가온",
                                  "category_name":"여행 > 숙박 > 호텔",
                                  "address_name":"세종특별자치시 어진동 123",
                                  "road_address_name":"세종특별자치시 한누리대로 1",
                                  "place_url":"https://place.map.kakao.com/1",
                                  "x":"127.2714",
                                  "y":"36.4912",
                                  "distance":"1200"
                                }]
                                """),
                        MediaType.APPLICATION_JSON));

        List<Poi> result = client.search(criteria(PoiCategory.LODGING, "세종 호텔"), 8);

        assertThat(result).singleElement().satisfies(poi -> {
            assertThat(poi.name()).isEqualTo("호텔 세종 가온");
            assertThat(poi.category()).isEqualTo(PoiCategory.LODGING);
            assertThat(poi.provider()).isEqualTo(PoiProvider.KAKAO);
            assertThat(poi.lat()).isEqualByComparingTo("36.4912");
            assertThat(poi.lng()).isEqualByComparingTo("127.2714");
            assertThat(poi.distanceM()).isEqualTo(1200);
            assertThat(poi.description()).isEqualTo("여행 > 숙박 > 호텔");
            assertThat(poi.address()).isEqualTo("세종특별자치시 한누리대로 1");
            assertThat(poi.url()).isEqualTo("https://place.map.kakao.com/1");
            assertThat(poi.imageUrl()).isNull();
        });
        server.verify();
    }

    @Test
    void query가_없는_NATURE는_명세의_자연_키워드를_사용한다() {
        server.expect(request -> {
                    var params = UriComponentsBuilder.fromUri(request.getURI())
                            .build()
                            .getQueryParams();
                    assertThat(request.getURI().getPath())
                            .isEqualTo("/v2/local/search/keyword.json");
                    assertThat(URLDecoder.decode(
                                    params.getFirst("query"),
                                    StandardCharsets.UTF_8))
                            .isEqualTo("둘레길 공원 산책로 수목원");
                })
                .andRespond(withSuccess(response(true, "[]"), MediaType.APPLICATION_JSON));

        assertThat(client.search(criteria(PoiCategory.NATURE, ""), 8)).isEmpty();
        server.verify();
    }

    @Test
    void 최대_20건을_위해_카카오_15건_제한을_페이지로_나눈다() {
        server.expect(request -> assertPage(request.getURI().toString(), "1", "15"))
                .andRespond(withSuccess(
                        response(false, documents(15, 0)),
                        MediaType.APPLICATION_JSON));
        server.expect(request -> assertPage(request.getURI().toString(), "2", "5"))
                .andRespond(withSuccess(
                        response(true, documents(5, 15)),
                        MediaType.APPLICATION_JSON));

        assertThat(client.search(criteria(PoiCategory.CAFE, ""), 20)).hasSize(20);
        server.verify();
    }

    @Test
    void 카카오_429는_한_번만_재시도한다() {
        server.expect(request -> {}).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(request -> {})
                .andRespond(withSuccess(response(true, "[]"), MediaType.APPLICATION_JSON));

        assertThat(client.search(criteria(PoiCategory.FOOD, ""), 8)).isEmpty();
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
        KakaoPoiClient missingKeyClient = new KakaoPoiClient(
                RestClient.create("https://dapi.kakao.test"),
                " ");

        assertThatThrownBy(() -> missingKeyClient.search(
                        criteria(PoiCategory.FOOD, ""),
                        8))
                .isInstanceOfSatisfying(
                        PoiSourceException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.ERROR));
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
        assertThatThrownBy(() -> client.search(criteria(PoiCategory.FOOD, ""), 8))
                .isInstanceOfSatisfying(
                        PoiSourceException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(expected));
    }

    private void assertPage(String uriText, String expectedPage, String expectedSize) {
        var params = UriComponentsBuilder.fromUriString(uriText).build().getQueryParams();
        assertThat(params.getFirst("page")).isEqualTo(expectedPage);
        assertThat(params.getFirst("size")).isEqualTo(expectedSize);
    }

    private String response(boolean isEnd, String documents) {
        return """
                {"meta":{"is_end":%s},"documents":%s}
                """.formatted(isEnd, documents);
    }

    private String documents(int count, int offset) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                json.append(',');
            }
            int number = offset + index;
            json.append("""
                    {"place_name":"카페 %d","category_name":"카페","address_name":"주소",
                    "road_address_name":"","place_url":"","x":"127.%04d","y":"36.49",
                    "distance":"%d"}
                    """.formatted(number, number, number + 1));
        }
        return json.append(']').toString();
    }
}
