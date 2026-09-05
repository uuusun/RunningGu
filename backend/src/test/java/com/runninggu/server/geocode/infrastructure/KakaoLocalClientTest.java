package com.runninggu.server.geocode.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.runninggu.server.common.upstream.UpstreamLoadGuard;
import com.runninggu.server.common.upstream.UpstreamLoadGuardException;
import com.runninggu.server.common.upstream.UpstreamLoadGuardInterceptor;
import com.runninggu.server.common.upstream.UpstreamLoadGuardProperties;
import com.runninggu.server.common.upstream.UpstreamLoadGuardProperties.EndpointLimits;
import com.runninggu.server.common.upstream.UpstreamProvider;
import com.runninggu.server.geocode.application.GeocodeProviderException;
import com.runninggu.server.geocode.application.GeocodeProviderException.Reason;
import com.runninggu.server.geocode.domain.GeocodeResult;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class KakaoLocalClientTest {

    private MockRestServiceServer server;
    private KakaoLocalClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoLocalClient(
                builder.baseUrl("https://dapi.kakao.test").build(),
                "test-rest-key");
    }

    @Test
    void 첫_장소의_도로명주소와_x경도_y위도를_앱_좌표로_바꾼다() {
        server.expect(request -> {
                    var uri = request.getURI();
                    var queryParams = UriComponentsBuilder.fromUri(uri)
                            .build()
                            .getQueryParams();
                    assertThat(uri.getPath()).isEqualTo("/v2/local/search/keyword.json");
                    assertThat(URLDecoder.decode(
                                    queryParams.getFirst("query"),
                                    StandardCharsets.UTF_8))
                            .isEqualTo("해운대해수욕장");
                    assertThat(queryParams.getFirst("size")).isEqualTo("1");
                })
                .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK test-rest-key"))
                .andRespond(withSuccess(
                        """
                        {
                          "documents": [{
                            "place_name": "해운대해수욕장",
                            "road_address_name": "부산 해운대구 해운대해변로 264",
                            "address_name": "부산 해운대구 우동 620-3",
                            "x": "129.1587",
                            "y": "35.1587"
                          }]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        Optional<GeocodeResult> result = client.findFirst("해운대해수욕장");

        assertThat(result).hasValueSatisfying(geocode -> {
            assertThat(geocode.name()).isEqualTo("해운대해수욕장");
            assertThat(geocode.address()).isEqualTo("부산 해운대구 해운대해변로 264");
            assertThat(geocode.lat()).isEqualByComparingTo("35.1587");
            assertThat(geocode.lng()).isEqualByComparingTo("129.1587");
        });
        server.verify();
    }

    @Test
    void 도로명주소가_없으면_지번주소를_쓴다() {
        server.expect(request -> {})
                .andRespond(withSuccess(
                        """
                        {"documents":[{
                          "place_name":"장소",
                          "road_address_name":"",
                          "address_name":"부산 해운대구 우동",
                          "x":"129.16",
                          "y":"35.16"
                        }]}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThat(client.findFirst("장소"))
                .get()
                .extracting(GeocodeResult::address)
                .isEqualTo("부산 해운대구 우동");
        server.verify();
    }

    @Test
    void 문서가_비어_있으면_정상_빈_결과다() {
        server.expect(request -> {})
                .andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.findFirst("없는 장소")).isEmpty();
        server.verify();
    }

    @Test
    void 카카오_429는_한_번만_재시도한다() {
        server.expect(request -> {})
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(request -> {})
                .andRespond(withSuccess(
                        """
                        {"documents":[{
                          "place_name":"장소",
                          "road_address_name":"주소",
                          "address_name":"",
                          "x":"127.0",
                          "y":"37.0"
                        }]}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThat(client.findFirst("장소")).isPresent();
        server.verify();
    }

    @Test
    void guard가_켜지면_첫_429에서_trip하고_재시도하지_않는다() {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(new UpstreamLoadGuardProperties(
                true,
                "staging",
                "kakao-429-test",
                100,
                new EndpointLimits(100, 100, 100, 100, 100, 100, 100, 100)));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer guardedServer = MockRestServiceServer.bindTo(builder).build();
        KakaoLocalClient guardedClient = new KakaoLocalClient(
                builder
                        .baseUrl("https://dapi.kakao.com")
                        .requestInterceptor(new UpstreamLoadGuardInterceptor(
                                guard,
                                UpstreamProvider.KAKAO))
                        .build(),
                "test-rest-key");
        guardedServer.expect(request -> {})
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> guardedClient.findFirst("장소"))
                .isInstanceOf(UpstreamLoadGuardException.class);
        guardedServer.verify();
    }

    @Test
    void 카카오_429가_두_번이면_외부오류다() {
        server.expect(request -> {})
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(request -> {})
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.findFirst("장소"))
                .isInstanceOfSatisfying(
                        GeocodeProviderException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.ERROR));
        server.verify();
    }

    @Test
    void 카카오_서버오류는_재시도하지_않고_외부오류다() {
        server.expect(request -> {})
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.findFirst("장소"))
                .isInstanceOfSatisfying(
                        GeocodeProviderException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.ERROR));
        server.verify();
    }

    @Test
    void 타임아웃은_별도_실패_종류로_전달한다() {
        server.expect(request -> {})
                .andRespond(withException(new SocketTimeoutException("timeout")));

        assertThatThrownBy(() -> client.findFirst("장소"))
                .isInstanceOfSatisfying(
                        GeocodeProviderException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.TIMEOUT));
        server.verify();
    }

    @Test
    void 잘못된_좌표는_외부오류다() {
        server.expect(request -> {})
                .andRespond(withSuccess(
                        """
                        {"documents":[{
                          "place_name":"장소",
                          "road_address_name":"주소",
                          "address_name":"",
                          "x":"not-a-number",
                          "y":"37.0"
                        }]}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.findFirst("장소"))
                .isInstanceOfSatisfying(
                        GeocodeProviderException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.ERROR));
        server.verify();
    }

    @Test
    void 서버키가_없으면_외부호출하지_않고_실패한다() {
        KakaoLocalClient missingKeyClient = new KakaoLocalClient(
                RestClient.create("https://dapi.kakao.test"),
                " ");

        assertThatThrownBy(() -> missingKeyClient.findFirst("장소"))
                .isInstanceOfSatisfying(
                        GeocodeProviderException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(Reason.ERROR));
    }
}
