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
import java.util.Set;
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
                .doesNotContain("숙소 체크아웃");
        // **체크아웃은 11시라 마지막이 아니라 오전과 점심 사이다** (#319).
        // 숙소 대부분이 그 시각이고, 짐을 뺀 뒤에도 그날 일정은 이어진다.
        assertThat(day(half, 2).blocks())
                .extracting(GeneratedBlock::title)
                .containsExactly("온천·족욕", "숙소 체크아웃", "로컬 점심", "오후 관광");
        assertThat(day(half, 2).blocks())
                .filteredOn(block -> block.title().equals("숙소 체크아웃"))
                .extracting(block -> block.startTime().toString())
                .containsExactly("11:00");
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

    // ── 추천 품질 (#319) ────────────────────────────────────────────────────

    /**
     * **식사 자리에 프랜차이즈 빵집·패스트푸드를 넣지 않는다.**
     *
     * 카카오 `FD6` 는 음식점을 통째로 담아서 파리바게뜨·맘스터치가 함께 온다. `sort=distance`
     * 로 받아 첫 항목을 집던 예전 방식에서는 그것이 저녁으로 뽑혔다.
     */
    @Test
    void 식사_자리에는_프랜차이즈_빵집과_패스트푸드를_넣지_않는다() {
        Map<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>(mealPools());
        places.put(PoiCategory.FOOD, List.of(
                place("파리바게뜨 거제서정점", "음식점 > 간식 > 제과,베이커리 > 파리바게뜨"),
                place("맘스터치 거제서정점", "음식점 > 패스트푸드 > 맘스터치"),
                place("각산애식당", "음식점 > 한식")));

        GeneratedItinerary result = generate(
                plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE, true),
                new PoiPools(places, sourcesOf(places)));

        assertThat(day(result, -1).blocks())
                .filteredOn(block -> block.title().contains("저녁"))
                .extracting(block -> block.place().name())
                .containsExactly("각산애식당");
    }

    /** 거를 것만 남으면 **빈 블록보다 낫다** — 거르지 않은 목록으로 돌아간다. */
    @Test
    void 식사로_쓸_곳이_하나도_없으면_거르지_않는다() {
        Map<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>(mealPools());
        places.put(PoiCategory.FOOD, List.of(
                place("파리바게뜨 거제서정점", "음식점 > 간식 > 제과,베이커리 > 파리바게뜨")));

        GeneratedItinerary result = generate(
                plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE, true),
                new PoiPools(places, sourcesOf(places)));

        assertThat(day(result, -1).blocks())
                .filteredOn(block -> block.title().contains("저녁"))
                .extracting(block -> block.place().name())
                .containsExactly("파리바게뜨 거제서정점");
    }

    /**
     * **같은 요청을 다시 하면 다른 곳이 나온다.**
     *
     * 예전에는 목록 순서대로 첫 미사용 항목을 집어서 매번 같은 동선이었다. 추천을 다시
     * 받아도 달라지지 않으면 추천이 아니라 고정 값이다.
     */
    @Test
    void 같은_조건이어도_매번_같은_곳만_고르지_않는다() {
        Map<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>(mealPools());
        List<ItineraryPlace> nearby = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            nearby.add(place("식당-" + index, "음식점 > 한식"));
        }
        places.put(PoiCategory.FOOD, nearby);
        PoiPools pools = new PoiPools(places, sourcesOf(places));

        Set<String> picked = new java.util.HashSet<>();
        for (int attempt = 0; attempt < 30; attempt++) {
            GeneratedItinerary result = generate(
                    plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE, true),
                    pools);
            day(result, -1).blocks().stream()
                    .filter(block -> block.title().contains("저녁"))
                    .forEach(block -> picked.add(block.place().name()));
        }

        assertThat(picked).hasSizeGreaterThan(1);
    }

    /** **너무 먼 곳은 후보에서 뺀다** — 하루 안에 못 도는 일정이 된다. */
    @Test
    void 가장_가까운_곳에서_멀리_떨어진_곳은_고르지_않는다() {
        Map<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>(mealPools());
        places.put(PoiCategory.FOOD, List.of(
                place("가까운식당", "음식점 > 한식"),
                // 위도 0.1도 ≈ 11km. NEARBY_SPREAD_M(2km) 밖이다.
                new ItineraryPlace("먼식당", "주소", new BigDecimal("34.9545247800561"),
                        new BigDecimal("128.576145697347"), "음식점 > 한식")));
        PoiPools pools = new PoiPools(places, sourcesOf(places));

        Set<String> picked = new java.util.HashSet<>();
        for (int attempt = 0; attempt < 30; attempt++) {
            GeneratedItinerary result = generate(
                    plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE, true),
                    pools);
            day(result, -1).blocks().stream()
                    .filter(block -> block.title().contains("저녁"))
                    .forEach(block -> picked.add(block.place().name()));
        }

        assertThat(picked).containsExactly("가까운식당");
    }

    /** 대회 전날 저녁 문구에서 **"카보로딩" 을 뺐다** — 러너가 아니면 모르는 말이다. */
    @Test
    void 대회_전날_저녁에_카보로딩이라는_말을_쓰지_않는다() {
        GeneratedItinerary result = generate(
                plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE, true),
                pools(8));

        assertThat(day(result, -1).blocks())
                .extracting(GeneratedBlock::title)
                .noneMatch(title -> title.contains("카보로딩"));
        assertThat(day(result, -1).blocks())
                .extracting(GeneratedBlock::description)
                .noneMatch(description -> description.contains("탄수화물"));
    }

    private ItineraryPlace place(String name, String categoryName) {
        return new ItineraryPlace(
                name,
                "경남 거제시",
                new BigDecimal("34.8545247800561"),
                new BigDecimal("128.576145697347"),
                categoryName);
    }

    /** FOOD 를 뺀 나머지 카테고리는 기본 풀을 쓴다. */
    private Map<PoiCategory, List<ItineraryPlace>> mealPools() {
        Map<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>();
        for (PoiCategory category : PoiCategory.values()) {
            List<ItineraryPlace> items = new ArrayList<>();
            for (int index = 1; index <= 8; index++) {
                items.add(place(category.name() + "-" + index, category.name()));
            }
            places.put(category, items);
        }
        return places;
    }

    private Map<PoiCategory, String> sourcesOf(Map<PoiCategory, List<ItineraryPlace>> places) {
        Map<PoiCategory, String> sources = new LinkedHashMap<>();
        places.keySet().forEach(category -> sources.put(category, "LIVE"));
        return sources;
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
