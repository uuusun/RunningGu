package com.runninggu.server.itinerary.domain;

import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.poi.domain.PoiCategory;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SPEC §5.6의 일정 골격과 회복 분기를 구현한 순수 생성 엔진이다.
 * Spring·JPA·외부 API를 모르며 준비된 POI 풀만 소비한다.
 */
public class ItineraryGenerator {

    private static final LocalTime DEFAULT_RACE_START_TIME = LocalTime.of(8, 0);

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
        Picker picker = new Picker(pools, plan.themes());
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
                        "카보로딩 저녁",
                        BlockCategory.FOOD,
                        picker.pick(PoiCategory.FOOD),
                        "탄수화물 보충 · 무리 없는 메뉴"));
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
                        ItineraryPlace tour = picker.pick(PoiCategory.TOUR);
                        blocks.add(block(
                                "14:30",
                                "가벼운 관광",
                                BlockCategory.TOUR,
                                tour,
                                descriptionOr(tour, "평지 위주 가벼운 코스")));
                    }
                    blocks.add(block(
                            "18:00",
                            "회복 저녁",
                            BlockCategory.FOOD,
                            picker.pick(PoiCategory.FOOD),
                            "소화 잘 되는 회복식"));
                } else {
                    PickedPlace theme = picker.pickTheme();
                    blocks.add(block(
                            "13:00",
                            "오후 자유 관광",
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
                            picker.pick(PoiCategory.FOOD),
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
                blocks.add(block(
                        "12:30",
                        "로컬 점심",
                        BlockCategory.FOOD,
                        picker.pick(PoiCategory.FOOD),
                        "그 지역 별미"));
                PickedPlace theme = picker.pickTheme();
                blocks.add(block(
                        "14:30",
                        "오후 관광",
                        blockCategory(theme.category()),
                        theme.place(),
                        ""));
                if (date.equals(plan.endDate())) {
                    blocks.add(block(
                            "17:00",
                            "체크아웃·귀가",
                            BlockCategory.LODGING,
                            hotel,
                            "여행 마무리"));
                }
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

    private static final class Picker {

        private final PoiPools pools;
        private final List<PoiCategory> themes;
        private final Set<String> usedNames = new LinkedHashSet<>();

        private Picker(PoiPools pools, List<PoiCategory> themes) {
            this.pools = pools;
            this.themes = List.copyOf(themes);
        }

        private ItineraryPlace pick(PoiCategory category) {
            List<ItineraryPlace> places = pools.get(category);
            for (ItineraryPlace place : places) {
                if (usedNames.add(place.name())) {
                    return place;
                }
            }
            return places.isEmpty() ? null : places.getFirst();
        }

        private PickedPlace pickTheme() {
            LinkedHashSet<PoiCategory> order = new LinkedHashSet<>(themes);
            order.add(PoiCategory.TOUR);
            order.add(PoiCategory.NATURE);
            order.add(PoiCategory.CAFE);
            order.add(PoiCategory.HISTORY);

            for (PoiCategory category : order) {
                if (pools.get(category).stream()
                        .anyMatch(place -> !usedNames.contains(place.name()))) {
                    return new PickedPlace(category, pick(category));
                }
            }
            return new PickedPlace(PoiCategory.TOUR, pick(PoiCategory.TOUR));
        }
    }

    private record PickedPlace(PoiCategory category, ItineraryPlace place) {}
}
