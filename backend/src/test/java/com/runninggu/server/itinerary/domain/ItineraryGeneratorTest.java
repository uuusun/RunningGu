package com.runninggu.server.itinerary.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.poi.domain.PoiCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItineraryGeneratorTest {

    private static final LocalDate RACE_DATE = LocalDate.of(2026, 10, 25);
    private final ItineraryGenerator generator = new ItineraryGenerator();

    @Test
    void 전날과_종목별_대회일과_마지막날_블록을_명세대로_만든다() {
        GeneratedItinerary half = generate(
                plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE.plusDays(2), true),
                pools(8));
        GeneratedItinerary full = generate(
                plan(ContestEventType.FULL, RACE_DATE.minusDays(1), RACE_DATE.plusDays(1), true),
                pools(8));
        GeneratedItinerary tenK = generate(
                plan(ContestEventType.K10, RACE_DATE.minusDays(1), RACE_DATE.plusDays(1), true),
                pools(8));

        assertThat(day(half, -1).blocks())
                .extracting(block -> block.startTime().toString())
                .containsExactly("15:00", "18:30");
        assertThat(day(half, 0).blocks())
                .extracting(GeneratedBlock::title)
                .containsExactly("🏁 춘천마라톤 스타트", "온천·회복", "가벼운 관광", "회복 저녁");
        assertThat(day(full, 0).blocks())
                .extracting(GeneratedBlock::title)
                .containsExactly("🏁 춘천마라톤 스타트", "온천·회복", "회복 저녁");
        assertThat(day(tenK, 0).blocks())
                .extracting(GeneratedBlock::title)
                .containsExactly("🏁 춘천마라톤 스타트", "오후 자유 관광", "카페 한 잔", "맛집 저녁");
        assertThat(day(half, 1).blocks())
                .extracting(GeneratedBlock::title)
                .doesNotContain("체크아웃·귀가");
        assertThat(day(half, 2).blocks())
                .extracting(GeneratedBlock::title)
                .endsWith("체크아웃·귀가");
    }

    @Test
    void 회복_배지와_일자_플래그를_D플러스_우선으로_계산한다() {
        GeneratedItinerary around = generate(
                plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE.plusDays(1), true),
                pools(8));
        GeneratedItinerary dayTrip = generate(
                plan(ContestEventType.FULL, RACE_DATE, RACE_DATE, true),
                pools(8));
        GeneratedItinerary normal = generate(
                plan(ContestEventType.K10, RACE_DATE.minusDays(1), RACE_DATE.plusDays(1), true),
                pools(8));

        assertThat(around.recovery().label()).isEqualTo("D+1 회복 모드");
        assertThat(around.days()).extracting(GeneratedDay::recovery)
                .containsExactly(false, false, true);
        assertThat(dayTrip.recovery().label()).isEqualTo("D-day 회복 모드");
        assertThat(dayTrip.days()).extracting(GeneratedDay::recovery)
                .containsExactly(true);
        assertThat(normal.recovery()).isNull();
        assertThat(normal.days()).extracting(GeneratedDay::recovery)
                .containsOnly(false);
    }

    @Test
    void 대회일_상대_오프셋과_지역없는_기간_제목을_반환한다() {
        GeneratedItinerary around = generate(
                plan(ContestEventType.K5, RACE_DATE.minusDays(1), RACE_DATE.plusDays(1), true),
                pools(8));
        GeneratedItinerary dayTrip = generate(
                plan(ContestEventType.K5, RACE_DATE, RACE_DATE, true),
                pools(8));

        assertThat(around.title()).isEqualTo("2박 3일");
        assertThat(around.days()).extracting(GeneratedDay::dayIndex)
                .containsExactly(-1, 0, 1);
        assertThat(around.days()).extracting(GeneratedDay::dayLabel)
                .containsExactly("D-1", "D-day", "D+1");
        assertThat(dayTrip.title()).isEqualTo("당일치기");
    }

    @Test
    void RACE만_시스템_관리하고_산책_블록은_만들지_않는다() {
        GeneratedItinerary generated = generate(
                plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE.plusDays(2), true),
                pools(8));
        List<GeneratedBlock> blocks = generated.days().stream()
                .flatMap(day -> day.blocks().stream())
                .toList();

        assertThat(blocks).filteredOn(GeneratedBlock::systemManaged)
                .singleElement()
                .satisfies(block -> {
                    assertThat(block.blockType()).isEqualTo(BlockType.RACE);
                    assertThat(block.place().name()).isEqualTo("춘천 공지천");
                    assertThat(block.place().address()).isEqualTo("강원 춘천시 공지로");
                });
        assertThat(blocks).extracting(GeneratedBlock::title)
                .noneMatch(title -> title.contains("산책"));
        assertThat(blocks).extracting(GeneratedBlock::startTime)
                .doesNotContain(LocalTime.of(20, 0), LocalTime.of(20, 30));
    }

    @Test
    void 테마와_회복_규칙으로_필요한_POI_카테고리를_결정한다() {
        ItineraryPlan half = plan(
                ContestEventType.HALF,
                RACE_DATE.minusDays(1),
                RACE_DATE.plusDays(1),
                true,
                List.of(PoiCategory.HISTORY));
        ItineraryPlan tenK = plan(
                ContestEventType.K10,
                RACE_DATE.minusDays(1),
                RACE_DATE.plusDays(1),
                true,
                List.of(PoiCategory.HISTORY));

        assertThat(generator.requiredCategories(half))
                .containsExactly(
                        PoiCategory.FOOD,
                        PoiCategory.TOUR,
                        PoiCategory.HISTORY,
                        PoiCategory.WELLNESS);
        assertThat(generator.requiredCategories(tenK))
                .containsExactly(
                        PoiCategory.FOOD,
                        PoiCategory.TOUR,
                        PoiCategory.HISTORY,
                        PoiCategory.CAFE);
        assertThat(generator.requiredCategories(half)).doesNotContain(PoiCategory.NATURE);
    }

    @Test
    void 장소는_전체_일정에서_중복을_피하고_풀이_소진되면_첫_장소를_재사용한다() {
        GeneratedItinerary enough = generate(
                plan(ContestEventType.K10, RACE_DATE.minusDays(1), RACE_DATE.plusDays(2), true),
                pools(8));
        List<String> uniqueCandidates = userPlaceNames(enough);
        assertThat(uniqueCandidates).doesNotHaveDuplicates();

        GeneratedItinerary exhausted = generate(
                plan(ContestEventType.K5, RACE_DATE.minusDays(1), RACE_DATE.plusDays(2), true),
                pools(1));
        List<String> foodNames = exhausted.days().stream()
                .flatMap(day -> day.blocks().stream())
                .filter(block -> block.category() == BlockCategory.FOOD)
                .map(GeneratedBlock::place)
                .map(ItineraryPlace::name)
                .toList();
        assertThat(foodNames).hasSizeGreaterThan(1);
        assertThat(foodNames).containsOnly("FOOD-1");
    }

    @Test
    void 숙소와_POI가_없어도_일정_골격과_대회_블록을_유지한다() {
        GeneratedItinerary generated = generate(
                plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE, false),
                emptyPools());

        GeneratedBlock checkIn = day(generated, -1).blocks().getFirst();
        assertThat(checkIn.place()).isNull();
        assertThat(checkIn.description()).isEqualTo("여장 풀기");
        assertThat(day(generated, 0).blocks()).filteredOn(GeneratedBlock::systemManaged)
                .hasSize(1);
        assertThat(generated.days()).isNotEmpty();
    }

    @Test
    void 대회_출발시각이_없으면_여덟시를_사용한다() {
        ItineraryPlan plan = plan(ContestEventType.HALF, RACE_DATE, RACE_DATE, true);
        ItineraryRace withoutTime = new ItineraryRace(
                plan.race().id(),
                plan.race().name(),
                plan.race().place(),
                plan.race().roadAddress(),
                plan.race().date(),
                null,
                plan.race().lat(),
                plan.race().lng());

        GeneratedItinerary generated = generate(
                new ItineraryPlan(
                        withoutTime,
                        plan.hotel(),
                        plan.event(),
                        plan.themes(),
                        plan.startDate(),
                        plan.endDate()),
                pools(8));

        assertThat(day(generated, 0).blocks().getFirst().startTime())
                .isEqualTo(LocalTime.of(8, 0));
    }

    private GeneratedItinerary generate(ItineraryPlan plan, PoiPools pools) {
        return generator.generate(plan, pools);
    }

    private GeneratedDay day(GeneratedItinerary generated, int offset) {
        return generated.days().stream()
                .filter(day -> day.dayIndex() == offset)
                .findFirst()
                .orElseThrow();
    }

    private ItineraryPlan plan(
            ContestEventType event,
            LocalDate start,
            LocalDate end,
            boolean withHotel) {
        return plan(event, start, end, withHotel, List.of(PoiCategory.TOUR, PoiCategory.FOOD));
    }

    private ItineraryPlan plan(
            ContestEventType event,
            LocalDate start,
            LocalDate end,
            boolean withHotel,
            List<PoiCategory> themes) {
        ItineraryRace race = new ItineraryRace(
                153,
                "춘천마라톤",
                "춘천 공지천",
                "강원 춘천시 공지로",
                RACE_DATE,
                LocalTime.of(9, 0),
                new BigDecimal("37.8700000"),
                new BigDecimal("127.7300000"));
        ItineraryHotel hotel = withHotel
                ? new ItineraryHotel(
                        "호텔 춘천",
                        new BigDecimal("37.8800000"),
                        new BigDecimal("127.7200000"))
                : null;
        return new ItineraryPlan(race, hotel, event, themes, start, end);
    }

    private PoiPools pools(int countPerCategory) {
        Map<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>();
        Map<PoiCategory, String> sources = new LinkedHashMap<>();
        for (PoiCategory category : PoiCategory.values()) {
            List<ItineraryPlace> items = new ArrayList<>();
            for (int index = 1; index <= countPerCategory; index++) {
                items.add(new ItineraryPlace(
                        category.name() + "-" + index,
                        "강원 춘천시 " + index + "로",
                        new BigDecimal("37.8700000"),
                        new BigDecimal("127.7300000"),
                        category.name() + " 설명"));
            }
            places.put(category, items);
            sources.put(category, "LIVE");
        }
        return new PoiPools(places, sources);
    }

    private PoiPools emptyPools() {
        return new PoiPools(Map.of(), Map.of());
    }

    private List<String> userPlaceNames(GeneratedItinerary generated) {
        return generated.days().stream()
                .flatMap(day -> day.blocks().stream())
                .filter(block -> block.category() != BlockCategory.RACE)
                .filter(block -> block.category() != BlockCategory.LODGING)
                .map(GeneratedBlock::place)
                .filter(java.util.Objects::nonNull)
                .map(ItineraryPlace::name)
                .toList();
    }
}
