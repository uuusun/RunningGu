package com.runninggu.server.course.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GraphHopperOsmRouteGeneratorTest {

    @Test
    void 목표의_078배와_seed_0부터_15까지_요청하고_차도_5퍼센트_그룹을_먼저_고른다() {
        List<Call> calls = new ArrayList<>();
        OsmRoundTripSource source = (lat, lng, requestedDistanceM, seed) -> {
            calls.add(new Call(requestedDistanceM, seed));
            if (seed == 0) {
                return Optional.of(candidate(seed, 5_000, 100, 600, 5));
            }
            if (seed == 1) {
                return Optional.of(candidate(seed, 5_400, 120, 400, 8));
            }
            return Optional.empty();
        };

        OsmRouteSearchResult result = new GraphHopperOsmRouteGenerator(source).generate(
                new BigDecimal("37.5000"),
                new BigDecimal("127.0000"),
                new BigDecimal("5"));

        assertThat(calls).hasSize(16);
        assertThat(calls).extracting(Call::seed).containsExactly(
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        assertThat(calls).extracting(Call::requestedDistanceM).containsOnly(3_900);
        assertThat(result.degraded()).isFalse();
        assertThat(result.route()).hasValueSatisfying(route -> {
            assertThat(route.dataSource().name()).isEqualTo("OSM_GENERATED");
            assertThat(route.name()).isEqualTo("내 주변 5km 완만 러닝코스");
            assertThat(route.routeKm()).isEqualByComparingTo("5.40");
            assertThat(route.routeId()).startsWith("osm:").hasSize(16);
            assertThat(route.elevationProfileM()).containsExactly(10, 20, 15);
            assertThat(route.shortfall()).isFalse();
        });
    }

    @Test
    void 네_품질_상한을_하나라도_넘는_후보는_완화하지_않고_정상_0건으로_처리한다() {
        OsmRoundTripSource source = (lat, lng, requestedDistanceM, seed) -> switch (seed) {
            case 0 -> Optional.of(candidate(seed, 3_749, 10, 0, 1));
            case 1 -> Optional.of(candidate(seed, 6_251, 10, 0, 1));
            case 2 -> Optional.of(candidate(seed, 5_000, 250, 0, 1));
            case 3 -> Optional.of(candidate(seed, 5_000, 10, 1_001, 1));
            case 4 -> Optional.of(candidate(seed, 5_000, 10, 0, 31));
            default -> Optional.empty();
        };

        OsmRouteSearchResult result = new GraphHopperOsmRouteGenerator(source).generate(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.valueOf(5));

        assertThat(result.route()).isEmpty();
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void GraphHopper_호출이_실패하면_남은_seed를_중단하고_부분_후보와_degraded를_보존한다() {
        List<Integer> seeds = new ArrayList<>();
        OsmRoundTripSource source = (lat, lng, requestedDistanceM, seed) -> {
            seeds.add(seed);
            if (seed == 0) {
                return Optional.of(candidate(seed, 5_000, 10, 0, 2));
            }
            throw new OsmRouteSourceException("실패");
        };

        OsmRouteSearchResult result = new GraphHopperOsmRouteGenerator(source).generate(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.valueOf(5));

        assertThat(seeds).containsExactly(0, 1);
        assertThat(result.route()).isPresent();
        assertThat(result.degraded()).isTrue();
    }

    private OsmRouteCandidate candidate(
            int seed,
            double distanceM,
            double gainM,
            double majorRoadDistanceM,
            int turns) {
        return new OsmRouteCandidate(
                seed,
                distanceM,
                gainM,
                10_000,
                majorRoadDistanceM,
                turns,
                List.of(
                        point("37.5000", "127.0000", "10"),
                        point("37.5100", "127.0100", "20"),
                        point("37.5000", "127.0000", "15")));
    }

    private OsmRouteCoordinate point(String lat, String lng, String elevation) {
        return new OsmRouteCoordinate(
                new BigDecimal(lat),
                new BigDecimal(lng),
                new BigDecimal(elevation));
    }

    private record Call(int requestedDistanceM, int seed) {}
}
