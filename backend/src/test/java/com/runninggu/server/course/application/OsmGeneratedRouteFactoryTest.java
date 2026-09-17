package com.runninggu.server.course.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.runninggu.server.course.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class OsmGeneratedRouteFactoryTest {

    @Test
    void 진입점_이름을_정제하고_실제_경로_거리로_이름과_파생값을_만든다() {
        OsmGeneratedRoute route = OsmGeneratedRouteFactory.create(
                geometry("5.60"),
                new BigDecimal("37.5000"),
                new BigDecimal("127.0000"),
                new BigDecimal("6"),
                " \u0000여의도\n공원\t ");

        assertThat(route.name()).isEqualTo("여의도공원 주변 6km 평지 러닝코스");
        assertThat(route.distanceM()).isPositive();
        assertThat(route.durationMin()).isEqualTo(51);
        assertThat(route.shortfall()).isTrue();
        assertThat(route.routeId()).startsWith("osm:").hasSize(16);
    }

    @Test
    void 이름이_비면_출발지_기본_이름을_쓰고_유니코드_코드포인트_50자로_자른다() {
        OsmGeneratedRoute fallback = OsmGeneratedRouteFactory.create(
                geometry("5.02"),
                new BigDecimal("37.5010"),
                new BigDecimal("127.0010"),
                new BigDecimal("5"),
                "\u0000\n\t");
        String emojiName = "😀".repeat(51);
        OsmGeneratedRoute truncated = OsmGeneratedRouteFactory.create(
                geometry("5.02"),
                new BigDecimal("37.5010"),
                new BigDecimal("127.0010"),
                new BigDecimal("5"),
                emojiName);

        assertThat(fallback.name()).isEqualTo("출발지 주변 5km 평지 러닝코스");
        String prefix = truncated.name().substring(0, truncated.name().indexOf(" 주변"));
        assertThat(prefix.codePointCount(0, prefix.length())).isEqualTo(50);
        assertThat(prefix).isEqualTo("😀".repeat(50));
    }

    private OsmRouteGeometry geometry(String routeKm) {
        return new OsmRouteGeometry(
                new BigDecimal("37.5010"),
                new BigDecimal("127.0010"),
                CourseDifficulty.EASY,
                new BigDecimal(routeKm),
                20,
                List.of(10, 20, 10),
                "???");
    }
}
