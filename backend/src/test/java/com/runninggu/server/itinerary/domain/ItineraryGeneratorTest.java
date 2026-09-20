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
                .containsExactly("🏁 춘천마라톤 스타트", "가벼운 관광", "회복 저녁");
        // 풀도 회복 골격에 취향 자리를 받는다 — 예전에는 하프만 열려 있었다(#377 리뷰 · 결정-72)
        assertThat(day(full, 0).blocks())
                .extracting(GeneratedBlock::title)
                .containsExactly("🏁 춘천마라톤 스타트", "가벼운 관광", "회복 저녁");
        assertThat(day(tenK, 0).blocks())
                .extracting(GeneratedBlock::title)
                .containsExactly("🏁 춘천마라톤 스타트", "가벼운 관광", "카페 한 잔", "맛집 저녁");
        assertThat(day(half, 1).blocks())
                .extracting(GeneratedBlock::title)
                .doesNotContain("숙소 체크아웃");
        // **체크아웃은 11시라 마지막이 아니라 오전과 점심 사이다** (#319).
        // 숙소 대부분이 그 시각이고, 짐을 뺀 뒤에도 그날 일정은 이어진다.
        assertThat(day(half, 2).blocks())
                .extracting(GeneratedBlock::title)
                .containsExactly("숙소 체크아웃", "로컬 점심", "가벼운 관광");
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

        // 회복일에는 웰니스를 담지 않는다 — 고른 경우에만 themes 로 들어온다(결정-74)
        assertThat(generator.requiredCategories(half))
                .containsExactly(
                        PoiCategory.FOOD,
                        PoiCategory.TOUR,
                        PoiCategory.HISTORY);
        assertThat(generator.requiredCategories(tenK))
                .containsExactly(
                        PoiCategory.FOOD,
                        PoiCategory.TOUR,
                        PoiCategory.HISTORY,
                        PoiCategory.CAFE);
        assertThat(generator.requiredCategories(half)).doesNotContain(PoiCategory.NATURE);
        // 웰니스를 고르면 그때는 담는다
        assertThat(generator.requiredCategories(plan(
                ContestEventType.HALF,
                RACE_DATE,
                RACE_DATE,
                false,
                List.of(PoiCategory.WELLNESS)))).contains(PoiCategory.WELLNESS);
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
    void 숙소가_없으면_체크인과_체크아웃을_만들지_않고_나머지_골격을_유지한다() {
        GeneratedItinerary generated = generate(
                plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE.plusDays(1), false),
                emptyPools());

        assertThat(generated.days())
                .flatExtracting(GeneratedDay::blocks)
                .extracting(GeneratedBlock::category)
                .doesNotContain(BlockCategory.LODGING);
        assertThat(day(generated, 0).blocks()).filteredOn(GeneratedBlock::systemManaged)
                .hasSize(1);
        assertThat(day(generated, -1).blocks())
                .filteredOn(block -> block.category() == BlockCategory.FOOD)
                .singleElement()
                .satisfies(block -> {
                    assertThat(block.place()).isNull();
                    assertThat(block.description()).isEqualTo("식당 정보를 불러오지 못했어요.");
                });
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


    // ── 취향 블록 (#377 · 결정-72) ──────────────────────────────────────────

    /**
     * **당일치기 하프에서도 고른 취향이 나온다.** (SPEC §5.6-4·5 · 결정-72)
     *
     * 예전 골격은 D-day 회복일에 온천(웰니스)·가벼운 관광(관광지)을 고정으로 박아서, 맛집·카페를
     * 골라도 저녁 식사 말고는 고른 것이 하나도 안 보였다(윤진 제보 · #377).
     */
    @Test
    void 당일치기_하프도_고른_취향을_취향_자리에_넣는다() {
        GeneratedItinerary generated = generate(
                plan(
                        ContestEventType.HALF,
                        RACE_DATE,
                        RACE_DATE,
                        false,
                        List.of(PoiCategory.FOOD, PoiCategory.CAFE)),
                pools(8));

        assertThat(day(generated, 0).blocks())
                .extracting(GeneratedBlock::title)
                .containsExactly("🏁 춘천마라톤 스타트", "카페 한 잔", "회복 저녁");
        assertThat(day(generated, 0).blocks())
                .filteredOn(block -> block.title().equals("카페 한 잔"))
                .extracting(GeneratedBlock::category)
                .containsExactly(BlockCategory.CAFE);
    }

    /**
     * **취향 카테고리는 날마다 돌아간다.** 예전에는 "미사용 POI 가 남은 첫 카테고리" 만 보아서
     * 카테고리마다 8건씩 있는 풀이 마르지 않아 며칠이든 같은 종류가 나왔다("이틀 다 온천").
     */
    @Test
    void 취향_카테고리는_날마다_돌아간다() {
        GeneratedItinerary generated = generate(
                plan(
                        ContestEventType.HALF,
                        RACE_DATE,
                        RACE_DATE.plusDays(2),
                        true,
                        List.of(PoiCategory.CAFE, PoiCategory.HISTORY)),
                pools(8));

        List<BlockCategory> themeCategories = generated.days().stream()
                .flatMap(day -> day.blocks().stream())
                .filter(block -> block.startTime().equals(LocalTime.of(14, 30)))
                .map(GeneratedBlock::category)
                .toList();

        assertThat(themeCategories).hasSize(3);
        assertThat(themeCategories).containsExactly(
                BlockCategory.CAFE, BlockCategory.HISTORY, BlockCategory.CAFE);
    }

    /** 그 날 골격이 이미 쓰는 카테고리는 취향 자리에서 피한다 — 하루에 카페가 둘이 되지 않게. */
    @Test
    void 취향_자리는_그_날_고정_카테고리를_피한다() {
        GeneratedItinerary generated = generate(
                plan(
                        ContestEventType.K10,
                        RACE_DATE,
                        RACE_DATE,
                        false,
                        List.of(PoiCategory.CAFE, PoiCategory.HISTORY)),
                pools(8));

        // 15:30 이 카페 고정이므로 13:00 취향 자리는 카페를 뒤로 미루고 역사·문화를 쓴다
        assertThat(day(generated, 0).blocks())
                .extracting(GeneratedBlock::title)
                .containsExactly("🏁 춘천마라톤 스타트", "역사·문화 탐방", "카페 한 잔", "맛집 저녁");
    }

    /** 맛집은 취향 후보가 아니다 — 식사 블록이 이미 쓴다. 맛집만 골라도 취향 자리엔 다른 것이 온다. */
    @Test
    void 맛집만_골라도_취향_자리에는_식당이_오지_않는다() {
        GeneratedItinerary generated = generate(
                plan(
                        ContestEventType.K10,
                        RACE_DATE,
                        RACE_DATE,
                        false,
                        List.of(PoiCategory.FOOD)),
                pools(8));

        GeneratedBlock themeBlock = day(generated, 0).blocks().stream()
                .filter(block -> block.startTime().equals(LocalTime.of(13, 0)))
                .findFirst()
                .orElseThrow();

        assertThat(themeBlock.category()).isNotEqualTo(BlockCategory.FOOD);
        assertThat(day(generated, 0).blocks())
                .filteredOn(block -> block.category() == BlockCategory.FOOD)
                .hasSize(1);
    }


    /**
     * **중간 날에는 저녁이 있고 마지막 날에는 없다.** (SPEC §5.6-4 · 결정-73)
     *
     * 저녁 블록이 D-1·D-day 에만 있어서 3박4일의 D+1 은 14:30 이 마지막이었다 — 묵는 날인데
     * 일정이 끊긴 것처럼 보인다. 마지막 날은 11:00 체크아웃 뒤 이동하므로 그대로 둔다.
     */
    @Test
    void 중간_날에는_저녁을_넣고_마지막_날에는_넣지_않는다() {
        GeneratedItinerary generated = generate(
                plan(ContestEventType.K10, RACE_DATE.minusDays(1), RACE_DATE.plusDays(2), true),
                pools(8));

        assertThat(day(generated, 1).blocks())
                .extracting(GeneratedBlock::title)
                .contains("로컬 저녁");
        assertThat(day(generated, 1).blocks())
                .filteredOn(block -> block.title().equals("로컬 저녁"))
                .extracting(block -> block.startTime().toString())
                .containsExactly("18:30");
        assertThat(day(generated, 2).blocks())
                .extracting(GeneratedBlock::title)
                .doesNotContain("로컬 저녁");
        // 마지막 날에만 체크아웃이 있다는 기존 계약도 그대로다
        assertThat(day(generated, 2).blocks())
                .extracting(GeneratedBlock::title)
                .contains("숙소 체크아웃");
    }

    /** 하루 뒤로 끝나는 일정은 그날이 곧 마지막이라 저녁이 붙지 않는다. */
    @Test
    void D플러스가_하루뿐이면_저녁을_넣지_않는다() {
        GeneratedItinerary generated = generate(
                plan(ContestEventType.HALF, RACE_DATE, RACE_DATE.plusDays(1), true),
                pools(8));

        assertThat(day(generated, 1).blocks())
                .extracting(GeneratedBlock::title)
                .doesNotContain("로컬 저녁");
    }


    /**
     * **풀 당일치기에도 취향 자리가 있다.** (#377 리뷰 · 결정-72)
     *
     * 하프에만 열었을 때는 풀 당일치기가 `스타트 → 온천 → 회복 저녁` 뿐이라, 맛집·카페를 골라도
     * 카페가 사라졌다 — 고른 것이 하나도 안 보이는 문제가 풀에서 가장 심했다.
     */
    @Test
    void 풀_당일치기도_고른_취향을_취향_자리에_넣는다() {
        GeneratedItinerary generated = generate(
                plan(
                        ContestEventType.FULL,
                        RACE_DATE,
                        RACE_DATE,
                        false,
                        List.of(PoiCategory.FOOD, PoiCategory.CAFE)),
                pools(8));

        assertThat(day(generated, 0).blocks())
                .extracting(GeneratedBlock::title)
                .containsExactly("🏁 춘천마라톤 스타트", "카페 한 잔", "회복 저녁");
    }

    /**
     * **회복 안내는 POI 설명이 있어도 사라지지 않는다.** (#377 리뷰)
     *
     * 카카오 POI 는 `category_name` 이 설명으로 들어와 거의 항상 non-empty 라, "설명이 비면 안내"
     * 로 두면 회복일인데도 안내가 한 번도 안 보인다.
     */
    @Test
    void 회복일_취향_블록은_POI_설명이_있어도_회복_안내를_남긴다() {
        GeneratedItinerary generated = generate(
                plan(
                        ContestEventType.HALF,
                        RACE_DATE,
                        RACE_DATE,
                        false,
                        List.of(PoiCategory.CAFE)),
                pools(8));

        GeneratedBlock themeBlock = day(generated, 0).blocks().stream()
                .filter(block -> block.startTime().equals(LocalTime.of(14, 30)))
                .findFirst()
                .orElseThrow();

        assertThat(themeBlock.place().description()).isNotEmpty();
        assertThat(themeBlock.description()).startsWith("완주 후 가볍게");
        assertThat(themeBlock.description()).contains(themeBlock.place().description());
    }

    /**
     * **취향 블록 설명을 비우지 않는다.** 서버가 빈 값을 `null` 로 정규화해 저장하는데(API 명세 §5),
     * 그 `null` 에서 저장 동선 복원이 깨지는 앱이 아직 기기에 남아 있다(#375 · #376).
     */
    @Test
    void 생성한_블록_설명은_비어_있지_않다() {
        GeneratedItinerary generated = generate(
                plan(ContestEventType.K10, RACE_DATE.minusDays(1), RACE_DATE.plusDays(2), true),
                pools(8));

        assertThat(generated.days())
                .flatExtracting(GeneratedDay::blocks)
                .extracting(GeneratedBlock::description)
                .allSatisfy(description -> assertThat(description).isNotEmpty());
    }


    /**
     * **희소한 풀에서도 그 날 고정 카테고리를 피한다.** (SPEC §5.6-5 · #377 리뷰)
     *
     * 최종 폴백이 제외를 풀어 버리면, 고른 취향과 관광지 풀이 비고 카페만 남았을 때 13:00 취향
     * 블록과 15:30 고정 슬롯이 **둘 다 카페**가 된다.
     */
    @Test
    void 후보가_희소해도_그_날_고정_카테고리를_취향_자리에_쓰지_않는다() {
        // 맛집만 고른 10K 당일치기 — 취향 후보에서 맛집이 빠지고, TOUR·NATURE·HISTORY 풀도 비어
        // 남은 것이 그 날 고정 슬롯인 카페뿐인 상황
        Map<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>();
        places.put(PoiCategory.FOOD, List.of(place("식당", "음식점 > 한식")));
        places.put(PoiCategory.CAFE, List.of(place("카페", "카페")));
        places.put(PoiCategory.TOUR, List.of());
        places.put(PoiCategory.NATURE, List.of());
        places.put(PoiCategory.HISTORY, List.of());

        GeneratedItinerary generated = generate(
                plan(ContestEventType.K10, RACE_DATE, RACE_DATE, false, List.of(PoiCategory.FOOD)),
                new PoiPools(places, sourcesOf(places)));

        List<GeneratedBlock> blocks = day(generated, 0).blocks();
        assertThat(blocks)
                .filteredOn(block -> block.category() == BlockCategory.CAFE)
                .hasSize(1);
        // 취향 자리는 관광지로 떨어지고, 풀이 비었으니 장소 없는 블록이 된다(NFR-3 과 같은 강등)
        GeneratedBlock themeBlock = blocks.stream()
                .filter(block -> block.startTime().equals(LocalTime.of(13, 0)))
                .findFirst()
                .orElseThrow();
        assertThat(themeBlock.category()).isEqualTo(BlockCategory.TOUR);
    }


    // ── 회복 = 부담 축소 (#377 · 결정-74) ──────────────────────────────────

    /**
     * **웰니스를 고르지 않았으면 온천이 들어가지 않는다.** (SPEC §5.6-4 · 결정-74)
     *
     * 예전에는 하프·풀이면 `11:00 온천·회복` 이 무조건 들어갔다. 웰니스는 실측상 가장 희소한
     * 카테고리라(대회장 아홉 곳에서 0~6건) 없는 지역에서는 장소 없는 빈 블록이 떴다.
     */
    @Test
    void 웰니스를_고르지_않으면_회복일에도_온천을_넣지_않는다() {
        GeneratedItinerary generated = generate(
                plan(
                        ContestEventType.HALF,
                        RACE_DATE,
                        RACE_DATE.plusDays(1),
                        true,
                        List.of(PoiCategory.FOOD, PoiCategory.CAFE)),
                pools(8));

        assertThat(generated.days())
                .flatExtracting(GeneratedDay::blocks)
                .extracting(GeneratedBlock::category)
                .doesNotContain(BlockCategory.WELLNESS);
    }

    /** 웰니스를 고르면 다른 취향과 같은 자격으로 취향 자리에 온다. */
    @Test
    void 웰니스를_고르면_취향_자리에_온천이_온다() {
        GeneratedItinerary generated = generate(
                plan(ContestEventType.HALF, RACE_DATE, RACE_DATE, false,
                        List.of(PoiCategory.WELLNESS)),
                pools(8));

        assertThat(day(generated, 0).blocks())
                .extracting(GeneratedBlock::title)
                .containsExactly("🏁 춘천마라톤 스타트", "온천·힐링", "회복 저녁");
    }

    /**
     * **회복일은 비회복일보다 선택 방문이 하나 적다.** 이것이 결정-74 가 말하는 "부담 축소" 다 —
     * 설명 문구가 아니라 일정의 양으로 드러난다.
     */
    @Test
    void 회복일은_비회복일보다_블록이_하나_적다() {
        List<PoiCategory> themes = List.of(PoiCategory.FOOD, PoiCategory.CAFE);
        GeneratedItinerary half = generate(
                plan(ContestEventType.HALF, RACE_DATE, RACE_DATE.plusDays(1), true, themes),
                pools(8));
        GeneratedItinerary tenK = generate(
                plan(ContestEventType.K10, RACE_DATE, RACE_DATE.plusDays(1), true, themes),
                pools(8));

        assertThat(day(half, 0).blocks()).hasSize(day(tenK, 0).blocks().size() - 1);
        assertThat(day(half, 1).blocks()).hasSize(day(tenK, 1).blocks().size() - 1);
    }

    /** 웰니스 후보가 0건인 지역에서도 장소 없는 블록이 생기지 않는다. */
    @Test
    void 웰니스_후보가_없어도_빈_블록을_만들지_않는다() {
        Map<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>(mealPools());
        places.put(PoiCategory.WELLNESS, List.of());

        GeneratedItinerary generated = generate(
                plan(ContestEventType.HALF, RACE_DATE, RACE_DATE, false,
                        List.of(PoiCategory.FOOD, PoiCategory.CAFE)),
                new PoiPools(places, sourcesOf(places)));

        assertThat(day(generated, 0).blocks())
                .filteredOn(block -> block.blockType() == BlockType.USER)
                .extracting(GeneratedBlock::place)
                .doesNotContainNull();
    }

    // ── 추천 품질 (#319) ────────────────────────────────────────────────────

    /** 일반 식당이 있으면 간식·패스트푸드보다 먼저 쓴다. (SPEC §5.6 · 결정-75) */
    @Test
    void 식사_자리에는_일반_식당을_후순위_후보보다_먼저_넣는다() {
        Map<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>(mealPools());
        places.put(PoiCategory.FOOD, List.of(
                mealPlace("파리바게뜨 거제서정점", MealSuitability.SECONDARY),
                mealPlace("맘스터치 거제서정점", MealSuitability.SECONDARY),
                mealPlace("각산애식당", MealSuitability.PREFERRED)));

        GeneratedItinerary result = generate(
                plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE, true),
                new PoiPools(places, sourcesOf(places)));

        assertThat(day(result, -1).blocks())
                .filteredOn(block -> block.title().contains("저녁"))
                .extracting(block -> block.place().name())
                .containsExactly("각산애식당");
    }

    /** 일반 식당이 없을 때만 간식·패스트푸드 계열을 후순위로 쓴다. */
    @Test
    void 일반_식당이_없으면_후순위_식사_후보를_사용한다() {
        Map<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>(mealPools());
        places.put(PoiCategory.FOOD, List.of(
                mealPlace("파리바게뜨 거제서정점", MealSuitability.SECONDARY)));

        GeneratedItinerary result = generate(
                plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE, true),
                new PoiPools(places, sourcesOf(places)));

        assertThat(day(result, -1).blocks())
                .filteredOn(block -> block.title().contains("저녁"))
                .extracting(block -> block.place().name())
                .containsExactly("파리바게뜨 거제서정점");
    }

    @Test
    void 주류_중심과_미확인_후보만_있으면_장소_없는_식사_블록을_유지한다() {
        Map<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>(mealPools());
        places.put(PoiCategory.FOOD, List.of(
                mealPlace("달빛맥주", MealSuitability.EXCLUDED),
                mealPlace("분류 없는 장소", MealSuitability.UNKNOWN)));

        GeneratedItinerary result = generate(
                plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE, true),
                new PoiPools(places, sourcesOf(places)));

        assertThat(day(result, -1).blocks())
                .filteredOn(block -> block.category() == BlockCategory.FOOD)
                .singleElement()
                .satisfies(block -> {
                    assertThat(block.place()).isNull();
                    assertThat(block.description()).isEqualTo(
                            "추천할 식당을 찾지 못했어요. 편집에서 장소를 추가해 보세요.");
                });
    }

    @Test
    void 주류_중심_후보는_후순위_후보가_있으면_폴백에서도_되살아나지_않는다() {
        Map<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>(mealPools());
        places.put(PoiCategory.FOOD, List.of(
                mealPlace("달빛맥주", MealSuitability.EXCLUDED),
                mealPlace("동네분식", MealSuitability.SECONDARY)));

        GeneratedItinerary result = generate(
                plan(ContestEventType.HALF, RACE_DATE.minusDays(1), RACE_DATE, true),
                new PoiPools(places, sourcesOf(places)));

        assertThat(day(result, -1).blocks())
                .filteredOn(block -> block.category() == BlockCategory.FOOD)
                .extracting(block -> block.place().name())
                .containsExactly("동네분식");
    }

    @Test
    void 모든_식사_설명은_제목이나_메뉴_효능_대신_방문_전_확인을_안내한다() {
        for (ContestEventType event : List.of(ContestEventType.HALF, ContestEventType.K10)) {
            GeneratedItinerary result = generate(
                    plan(event, RACE_DATE.minusDays(1), RACE_DATE.plusDays(2), true),
                    pools(8));

            assertThat(result.days())
                    .flatExtracting(GeneratedDay::blocks)
                    .filteredOn(block -> block.category() == BlockCategory.FOOD)
                    .hasSize(5)
                    .allSatisfy(block -> {
                        assertThat(block.place()).isNotNull();
                        assertThat(block.description())
                                .isEqualTo("방문 전 메뉴와 영업시간을 확인해 주세요.")
                                .doesNotContain(block.title());
                    });
        }
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

    private ItineraryPlace mealPlace(String name, MealSuitability suitability) {
        return new ItineraryPlace(
                name,
                "경남 거제시",
                new BigDecimal("34.8545247800561"),
                new BigDecimal("128.576145697347"),
                "표시용 설명",
                suitability);
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
