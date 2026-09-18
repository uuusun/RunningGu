package com.runninggu.server.itinerary.domain;

import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.course.domain.GeoDistance;
import com.runninggu.server.poi.domain.PoiCategory;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * SPEC §5.6의 일정 골격과 회복 분기를 구현한 순수 생성 엔진이다.
 * Spring·JPA·외부 API를 모르며 준비된 POI 풀만 소비한다.
 */
public class ItineraryGenerator {

    private static final LocalTime DEFAULT_RACE_START_TIME = LocalTime.of(8, 0);

    /**
     * 가장 가까운 후보에서 이만큼 안쪽만 무작위 대상으로 둔다.
     *
     * 넓히면 매번 더 다양해지지만 하루 안에 못 도는 일정이 된다. 2km 는 걸어서 25분쯤이라
     * 대회 전후 일정에서 감당할 수 있는 폭으로 잡았다.
     */
    private static final double NEARBY_SPREAD_M = 2_000;

    /**
     * 식사 자리에서 뺄 카카오 카테고리. `category_name` 둘째 칸을 본다.
     *
     * `음식점 > 간식 > 제과,베이커리 > 파리바게뜨` · `음식점 > 패스트푸드 > 맥도날드` 처럼
     * 온다. 저녁·점심 블록에 빵집이나 패스트푸드가 오는 것을 막는다.
     */
    /**
     * 고른 취향으로 취향 자리를 못 채울 때 쓰는 기본 후보. (SPEC §5.6-5)
     *
     * 고른 것을 다 돌고도 자리가 남을 때만 여기까지 온다 — 이 순서가 앞에 오면 안 고른
     * 카테고리가 고른 것보다 먼저 나온다.
     */
    private static final List<PoiCategory> FALLBACK_THEMES = List.of(
            PoiCategory.TOUR,
            PoiCategory.NATURE,
            PoiCategory.CAFE,
            PoiCategory.HISTORY);

    /**
     * 회복일 취향 블록의 설명 기본값. (SPEC §5.6-5 · §5.1)
     *
     * 회복일이라고 취향 카테고리를 막지는 않는다 — POI 원천이 평지·실내 위주다. 대신 강도를
     * 문구로 낮춘다.
     */
    private static final String RECOVERY_THEME_DESCRIPTION = "완주 후 가볍게";

    private static final List<String> NON_MEAL_CATEGORIES = List.of(
            "음식점 > 간식",
            "음식점 > 패스트푸드");

    /** 필요한 카테고리를 결정적 순서로 한 번씩만 반환한다. (SPEC §5.6-2) */
    public List<PoiCategory> requiredCategories(ItineraryPlan plan) {
        Set<PoiCategory> categories = new LinkedHashSet<>();
        categories.add(PoiCategory.FOOD);
        categories.add(PoiCategory.TOUR);
        categories.addAll(plan.themes());
        categories.add(RecoveryPolicy.forEvent(plan.event()).noHard()
                ? PoiCategory.WELLNESS
                : PoiCategory.CAFE);
        return List.copyOf(categories);
    }

    public GeneratedItinerary generate(ItineraryPlan plan, PoiPools pools) {
        List<LocalDate> dates = plan.startDate()
                .datesUntil(plan.endDate().plusDays(1))
                .toList();
        List<Integer> offsets = dates.stream()
                .map(date -> Math.toIntExact(ChronoUnit.DAYS.between(plan.race().date(), date)))
                .toList();
        boolean hasPlusDay = offsets.stream().anyMatch(offset -> offset > 0);
        RecoveryRule rule = RecoveryPolicy.forEvent(plan.event());
        Picker picker = new Picker(pools, plan.themes(), new Random());
        ItineraryPlace hotel = hotelPlace(plan.hotel());
        ItineraryPlace raceVenue = racePlace(plan.race());

        List<GeneratedDay> days = new ArrayList<>();
        for (int index = 0; index < dates.size(); index++) {
            LocalDate date = dates.get(index);
            int offset = offsets.get(index);
            List<GeneratedBlock> blocks = new ArrayList<>();
            String note;

            if (offset < 0) {
                blocks.add(block(
                        "15:00",
                        "숙소 체크인",
                        BlockCategory.LODGING,
                        hotel,
                        "여장 풀기"));
                blocks.add(block(
                        "18:30",
                        "대회 전날 저녁",
                        BlockCategory.FOOD,
                        picker.pickMeal(),
                        "속 편한 메뉴로 가볍게"));
                note = "내일 완주 · 가볍게 먹고 푹 쉬기";
            } else if (offset == 0) {
                blocks.add(new GeneratedBlock(
                        plan.race().startTime() == null
                                ? DEFAULT_RACE_START_TIME
                                : plan.race().startTime(),
                        "🏁 " + plan.race().name() + " 스타트",
                        BlockCategory.RACE,
                        raceVenue,
                        eventLabel(plan.event()) + " 완주 · 결승 후 샤워",
                        BlockType.RACE));
                if (rule.noHard()) {
                    blocks.add(block(
                            "11:00",
                            "온천·회복",
                            BlockCategory.WELLNESS,
                            picker.pick(PoiCategory.WELLNESS),
                            "완주 근육 회복"));
                    if (plan.event() == ContestEventType.HALF) {
                        // 회복 골격에도 취향 자리를 연다 (SPEC §5.6-4·5 · 결정-72).
                        // 예전에는 관광지 고정이라 **당일치기 하프는 고른 것이 하나도 안 보였다**(#377).
                        // 같은 날 11:00 온천이 이미 웰니스를 쓰므로 그것만 뒤로 미룬다.
                        PickedPlace theme = picker.pickTheme(PoiCategory.WELLNESS);
                        blocks.add(block(
                                "14:30",
                                themeTitle(theme.category()),
                                blockCategory(theme.category()),
                                theme.place(),
                                descriptionOr(theme.place(), RECOVERY_THEME_DESCRIPTION)));
                    }
                    blocks.add(block(
                            "18:00",
                            "회복 저녁",
                            BlockCategory.FOOD,
                            picker.pickMeal(),
                            "소화 잘 되는 회복식"));
                } else {
                    // 같은 날 15:30 이 카페 고정이라 취향 자리는 카페를 뒤로 미룬다(§5.6-5).
                    PickedPlace theme = picker.pickTheme(PoiCategory.CAFE);
                    blocks.add(block(
                            "13:00",
                            themeTitle(theme.category()),
                            blockCategory(theme.category()),
                            theme.place(),
                            ""));
                    blocks.add(block(
                            "15:30",
                            "카페 한 잔",
                            BlockCategory.CAFE,
                            picker.pick(PoiCategory.CAFE),
                            "완주 후 휴식"));
                    blocks.add(block(
                            "18:30",
                            "맛집 저녁",
                            BlockCategory.FOOD,
                            picker.pickMeal(),
                            "오늘은 잘 먹는 날"));
                }
                note = rule.dday();
            } else {
                if (rule.noHard()) {
                    blocks.add(block(
                            "10:00",
                            "온천·족욕",
                            BlockCategory.WELLNESS,
                            picker.pick(PoiCategory.WELLNESS),
                            "고강도 제외 · 회복 위주"));
                } else {
                    blocks.add(block(
                            "10:00",
                            "오전 관광",
                            BlockCategory.TOUR,
                            picker.pick(PoiCategory.TOUR),
                            ""));
                }
                // **체크아웃은 11시다** — 숙소 대부분이 그 시각이라 그때 짐을 뺀다.
                // 마지막 블록으로 두면 `17:00 체크아웃` 이 되어 실제 일정과 어긋난다(#319).
                if (date.equals(plan.endDate())) {
                    blocks.add(block(
                            "11:00",
                            "숙소 체크아웃",
                            BlockCategory.LODGING,
                            hotel,
                            "짐 정리하고 나서기"));
                }
                blocks.add(block(
                        "12:30",
                        "로컬 점심",
                        BlockCategory.FOOD,
                        picker.pickMeal(),
                        "그 지역 별미"));
                // 그 날 오전이 이미 쓴 카테고리는 뒤로 미룬다 — 회복일은 온천, 아니면 오전 관광(§5.6-5).
                PickedPlace theme = picker.pickTheme(
                        rule.noHard() ? PoiCategory.WELLNESS : PoiCategory.TOUR);
                blocks.add(block(
                        "14:30",
                        themeTitle(theme.category()),
                        blockCategory(theme.category()),
                        theme.place(),
                        rule.noHard()
                                ? descriptionOr(theme.place(), RECOVERY_THEME_DESCRIPTION)
                                : ""));
                note = rule.dplus();
            }

            days.add(new GeneratedDay(
                    offset,
                    date,
                    RecoveryPolicy.dayLabel(offset),
                    RecoveryPolicy.isRecoveryDay(plan.event(), offset, hasPlusDay),
                    note,
                    blocks));
        }

        return new GeneratedItinerary(
                durationTitle(dates.size()),
                plan,
                RecoveryPolicy.recoveryFor(plan.event(), offsets),
                days,
                pools.sources());
    }

    private GeneratedBlock block(
            String startTime,
            String title,
            BlockCategory category,
            ItineraryPlace place,
            String description) {
        String resolvedDescription = description;
        if (resolvedDescription.isEmpty() && place != null) {
            resolvedDescription = place.description();
        }
        return new GeneratedBlock(
                LocalTime.parse(startTime),
                title,
                category,
                place,
                resolvedDescription,
                BlockType.USER);
    }

    private ItineraryPlace hotelPlace(ItineraryHotel hotel) {
        if (hotel == null) {
            return null;
        }
        return new ItineraryPlace(
                hotel.name(),
                null,
                hotel.lat(),
                hotel.lng(),
                "숙소");
    }

    private ItineraryPlace racePlace(ItineraryRace race) {
        return new ItineraryPlace(
                race.place(),
                race.roadAddress(),
                race.lat(),
                race.lng(),
                "대회장");
    }

    private String durationTitle(int dayCount) {
        return dayCount == 1 ? "당일치기" : (dayCount - 1) + "박 " + dayCount + "일";
    }

    private String eventLabel(ContestEventType event) {
        return switch (event) {
            case K5 -> "5K";
            case K10 -> "10K";
            case HALF -> "하프";
            case FULL -> "풀";
        };
    }

    private String descriptionOr(ItineraryPlace place, String fallback) {
        return place == null || place.description().isEmpty()
                ? fallback
                : place.description();
    }

    private BlockCategory blockCategory(PoiCategory category) {
        return BlockCategory.valueOf(category.name());
    }

    /**
     * 취향 블록의 제목. **뽑힌 카테고리를 따른다** (SPEC §5.6-5 · 결정-72).
     *
     * 예전에는 `오후 관광`·`가벼운 관광` 처럼 관광지를 전제한 이름이 박혀 있었다. 취향 자리를
     * 열고 나면 그 자리에 카페·산책로가 올 수 있어서, 제목이 내용과 어긋난다.
     *
     * 맛집은 취향 후보에서 빠지고(식사 블록과 겹친다) 숙소는 취향 칩에 없지만, `switch` 가
     * 모든 값을 덮어야 해서 둘도 관광지 이름으로 떨어뜨린다.
     *
     * **제목에 "산책" 을 쓰지 않는다.** 원본에 있던 산책 블록 3개는 §5.6 에서 뺐는데(대조표 A3),
     * 취향 블록에 그 이름을 붙이면 되살아난 것처럼 보이고 회귀 테스트도 구분하지 못한다.
     */
    private String themeTitle(PoiCategory category) {
        return switch (category) {
            case CAFE -> "카페 한 잔";
            case WELLNESS -> "온천·힐링";
            case NATURE -> "공원·둘레길";
            case HISTORY -> "역사·문화 탐방";
            case TOUR, FOOD, LODGING -> "가벼운 관광";
        };
    }

    private static final class Picker {

        private final PoiPools pools;
        private final List<PoiCategory> themes;
        private final Set<String> usedNames = new LinkedHashSet<>();
        /** 이번 일정에서 취향 자리로 이미 쓴 카테고리. 같은 종류가 날마다 반복되는 것을 막는다. */
        private final Set<PoiCategory> usedThemeCategories = new LinkedHashSet<>();
        private final Random random;

        private Picker(PoiPools pools, List<PoiCategory> themes, Random random) {
            this.pools = pools;
            this.themes = List.copyOf(themes);
            this.random = random;
        }

        /**
         * 카테고리에서 아직 안 쓴 장소 하나. **가까운 것들 중에서 무작위로** 고른다.
         *
         * 예전에는 목록 순서대로 첫 미사용 항목을 집었다. `sort=distance` 라 늘 가장 가까운
         * 곳이었고, **같은 대회·같은 조건이면 매번 같은 동선**이 나왔다. 다시 받아도 달라지지
         * 않으니 추천이라기보다 고정 값이었다.
         *
         * 그렇다고 전체에서 뽑으면 8km 밖이 걸려 하루 안에 못 도는 일정이 된다. 그래서
         * **가장 가까운 후보에서 {@link #NEARBY_SPREAD_M} 안**으로 창을 좁히고 그 안에서만
         * 무작위로 고른다. 창 안이 비면 순서대로 집던 예전 방식으로 돌아간다.
         */
        private ItineraryPlace pick(PoiCategory category) {
            return pickFrom(pools.get(category));
        }

        /**
         * **식사 자리 전용.** 프랜차이즈 빵집·패스트푸드를 뺀다.
         *
         * 카카오 `category_name` 이 계층으로 온다 — `음식점 > 간식 > 제과,베이커리 > 파리바게뜨`
         * 처럼 넷째 칸에 브랜드가 붙는다. 저녁 자리에 `파리바게뜨` 가 오던 것이 이 때문이다
         * (`FD6` 는 빵집·패스트푸드를 다 담는다).
         *
         * **카페는 거르지 않는다.** 카페 블록은 `CE7` 로 따로 불러오고, 거기서는 프랜차이즈가
         * 문제가 되지 않는다.
         *
         * 걸러서 아무것도 안 남으면 **거르지 않은 목록**으로 돌아간다 — 시골 대회장 주변에
         * 프랜차이즈뿐일 수 있고, 그때는 빈 블록보다 낫다.
         */
        private ItineraryPlace pickMeal() {
            List<ItineraryPlace> all = pools.get(PoiCategory.FOOD);
            List<ItineraryPlace> meals = all.stream()
                    .filter(Picker::isMeal)
                    .toList();
            ItineraryPlace picked = pickFrom(meals);
            return picked != null ? picked : pickFrom(all);
        }

        /** 식사로 볼 수 있는 곳인가. `description` 에 카카오 `category_name` 이 들어 있다. */
        private static boolean isMeal(ItineraryPlace place) {
            String category = place.description();
            if (category == null || category.isBlank()) {
                return true;
            }
            for (String excluded : NON_MEAL_CATEGORIES) {
                if (category.contains(excluded)) {
                    return false;
                }
            }
            return true;
        }

        private ItineraryPlace pickFrom(List<ItineraryPlace> places) {
            List<ItineraryPlace> unused = places.stream()
                    .filter(place -> !usedNames.contains(place.name()))
                    .toList();
            if (unused.isEmpty()) {
                return places.isEmpty() ? null : places.getFirst();
            }
            // `sort=distance` 로 받아 오므로 첫 항목이 가장 가깝다. 그 근처만 후보로 둔다.
            ItineraryPlace nearest = unused.getFirst();
            List<ItineraryPlace> nearby = unused.stream()
                    .filter(place -> withinSpread(nearest, place))
                    .toList();
            List<ItineraryPlace> candidates = nearby.isEmpty() ? unused : nearby;
            ItineraryPlace picked = candidates.get(random.nextInt(candidates.size()));
            usedNames.add(picked.name());
            return picked;
        }

        private static boolean withinSpread(ItineraryPlace from, ItineraryPlace to) {
            return GeoDistance.meters(from.lat(), from.lng(), to.lat(), to.lng())
                    <= NEARBY_SPREAD_M;
        }

        /**
         * 취향 자리에 넣을 카테고리와 장소를 고른다. (SPEC §5.6-5 · 결정-72)
         *
         * 후보 순서는 `[...themes, tour, nature, cafe, history]` 이고 **맛집은 뺀다** — 식사
         * 블록이 이미 맛집을 쓰므로 취향 자리에 또 식당이 오면 하루에 식당만 셋이 된다.
         *
         * **같은 카테고리가 반복되지 않게 미룬다.** 예전에는 "미사용 POI 가 남은 첫 카테고리"
         * 만 보았는데, 카테고리마다 POI 를 8건씩 담아 두니 첫 후보가 마르지 않아 며칠이 지나도
         * 같은 종류만 나왔다("이틀 다 온천"·"이틀 다 카페" · #377). 그래서 이번 일정에서 취향
         * 자리로 쓴 카테고리와, 그 날 골격이 이미 쓰는 고정 카테고리를 뒤로 미룬다.
         *
         * 고른 취향을 한 바퀴 돌고 나면 다시 처음부터 돈다 — 미루는 것이지 막는 것이 아니다.
         *
         * @param sameDayFixed 그 날 골격이 이미 쓰는 카테고리(회복일 온천 · 카페 슬롯 · 오전 관광)
         */
        private PickedPlace pickTheme(PoiCategory... sameDayFixed) {
            Set<PoiCategory> sameDay = Set.of(sameDayFixed);
            Set<PoiCategory> avoid = new LinkedHashSet<>(usedThemeCategories);
            avoid.addAll(sameDay);
            List<PoiCategory> picked = pickedThemes();

            // ① 고른 취향 중 이번 라운드에 아직 안 쓴 것
            PickedPlace fresh = pickFirstAvailable(without(picked, avoid));
            if (fresh != null) {
                return fresh;
            }
            // ② 고른 것을 한 바퀴 다 돌았다 — 라운드를 비우고 처음부터 다시 돈다.
            //    **기본 후보로 넘어가기 전에 이걸 먼저 한다.** 고른 것이 관광지뿐인데 카페가
            //    나오면 "안 골랐는데 나온다" 는 #377 의 불만을 그대로 되풀이한다.
            usedThemeCategories.clear();
            PickedPlace again = pickFirstAvailable(without(picked, sameDay));
            if (again != null) {
                return again;
            }
            // ③ 고른 것으로 못 채운다(POI 가 없거나 그 날 고정 카테고리와 겹친다)
            PickedPlace base = pickFirstAvailable(without(FALLBACK_THEMES, avoid));
            if (base != null) {
                return base;
            }
            PickedPlace last = pickFirstAvailable(FALLBACK_THEMES);
            if (last != null) {
                return last;
            }
            return new PickedPlace(PoiCategory.TOUR, pick(PoiCategory.TOUR));
        }

        /**
         * 취향 자리에 쓸 수 있는 **고른 취향**. 순서는 §5.3 선언 순서 그대로다.
         *
         * 맛집은 뺀다 — 식사 블록이 이미 쓰므로 취향 자리에 또 식당이 오면 하루에 식당만 셋이 된다.
         */
        private List<PoiCategory> pickedThemes() {
            return themes.stream()
                    .filter(category -> category != PoiCategory.FOOD)
                    .toList();
        }

        private List<PoiCategory> without(List<PoiCategory> categories, Set<PoiCategory> excluded) {
            return categories.stream()
                    .filter(category -> !excluded.contains(category))
                    .toList();
        }

        /** 미사용 POI 가 남아 있는 첫 카테고리를 쓴다. 없으면 null 이라 호출부가 다음 수를 고른다. */
        private PickedPlace pickFirstAvailable(List<PoiCategory> categories) {
            for (PoiCategory category : categories) {
                if (pools.get(category).stream()
                        .anyMatch(place -> !usedNames.contains(place.name()))) {
                    usedThemeCategories.add(category);
                    return new PickedPlace(category, pick(category));
                }
            }
            return null;
        }
    }

    private record PickedPlace(PoiCategory category, ItineraryPlace place) {}
}
