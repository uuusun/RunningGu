package com.runninggu.server.course.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.course.application.OsmRouteSourceException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GraphHopperClientTest {

    private MockRestServiceServer server;
    private GraphHopperClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GraphHopperClient(
                builder.baseUrl("http://graphhopper.test:8989").build(),
                new ObjectMapper(),
                true);
    }

    @Test
    void run_round_trip을_요청하고_차도_구간의_좌표_실거리와_실제_회전만_센다() {
        server.expect(request -> {
                    String query = request.getURI().getRawQuery();
                    assertThat(query)
                            .contains("point=37.5,127.0")
                            .contains("profile=run")
                            .contains("algorithm=round_trip")
                            .contains("round_trip.distance=3900")
                            .contains("round_trip.seed=7")
                            .contains("points_encoded=false")
                            .contains("elevation=true")
                            .contains("instructions=true")
                            .contains("details=road_class");
                })
                .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));

        var candidate = client.fetch(
                        new BigDecimal("37.5"),
                        new BigDecimal("127.0"),
                        3_900,
                        7)
                .orElseThrow();

        assertThat(candidate.seed()).isEqualTo(7);
        assertThat(candidate.distanceM()).isEqualTo(900);
        assertThat(candidate.gainM()).isEqualTo(10);
        assertThat(candidate.turnCount()).isEqualTo(3);
        assertThat(candidate.majorRoadDistanceM()).isBetween(8.0, 10.0);
        assertThat(candidate.polylineDistanceM()).isGreaterThan(890);
        assertThat(candidate.coordinates()).hasSize(3);
        server.verify();
    }

    @Test
    void paths가_비어_있으면_장애가_아니라_정상_후보_0건이다() {
        server.expect(request -> {})
                .andRespond(withSuccess("{\"paths\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.fetch(BigDecimal.ZERO, BigDecimal.ZERO, 1_000, 0)).isEmpty();
        server.verify();
    }

    @Test
    void road_class_인덱스가_좌표를_벗어나면_응답_장애로_처리한다() {
        String body = successBody().replace("[1,2,\"footway\"]", "[1,3,\"footway\"]");
        server.expect(request -> {}).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetch(BigDecimal.ZERO, BigDecimal.ZERO, 1_000, 0))
                .isInstanceOf(OsmRouteSourceException.class);
        server.verify();
    }

    @Test
    void 비활성화하면_외부_호출_없이_장애로_처리한다() {
        GraphHopperClient disabled = new GraphHopperClient(
                RestClient.create("http://graphhopper.test:8989"),
                new ObjectMapper(),
                false);

        assertThatThrownBy(() -> disabled.fetch(BigDecimal.ZERO, BigDecimal.ZERO, 1_000, 0))
                .isInstanceOf(OsmRouteSourceException.class);
    }

    private String successBody() {
        return """
                {
                  "paths": [{
                    "distance": 900,
                    "points": {"coordinates": [
                      [127.0000, 37.0000, 10],
                      [127.0001, 37.0000, 15],
                      [127.0101, 37.0000, 20]
                    ]},
                    "details": {"road_class": [
                      [0,1,"primary"],
                      [1,2,"footway"]
                    ]},
                    "instructions": [
                      {"sign": 0}, {"sign": -2}, {"sign": 2},
                      {"sign": 6}, {"sign": 4}, {"sign": 1}
                    ]
                  }]
                }
                """;
    }
}
