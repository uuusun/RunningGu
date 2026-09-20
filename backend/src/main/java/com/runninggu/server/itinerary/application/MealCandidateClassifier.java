package com.runninggu.server.itinerary.application;

import com.runninggu.server.itinerary.domain.MealSuitability;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 원천 업종을 표시 문구와 분리해 자동 식사 후보 등급으로 바꾼다. (SPEC §5.6 · 결정-75) */
final class MealCandidateClassifier {

    private MealCandidateClassifier() {}

    static MealSuitability classify(Poi poi) {
        if (poi.category() != PoiCategory.FOOD) {
            return MealSuitability.PREFERRED;
        }
        if (poi.sourceCategory().isBlank()) {
            return MealSuitability.UNKNOWN;
        }
        return switch (poi.provider()) {
            case KAKAO -> classifyKakao(poi.sourceCategory());
            case KTO -> classifyKto(poi.sourceCategory());
        };
    }

    private static MealSuitability classifyKakao(String sourceCategory) {
        List<String> path = Arrays.stream(sourceCategory.split(">"))
                .map(String::strip)
                .filter(segment -> !segment.isEmpty())
                .toList();
        if (path.isEmpty() || !path.getFirst().equals("음식점")) {
            return MealSuitability.UNKNOWN;
        }
        if (path.stream().anyMatch(MealCandidateClassifier::isAlcoholCategory)) {
            return MealSuitability.EXCLUDED;
        }
        if (path.stream().anyMatch(segment -> segment.equals("간식")
                || segment.equals("패스트푸드"))) {
            return MealSuitability.SECONDARY;
        }
        return MealSuitability.PREFERRED;
    }

    private static boolean isAlcoholCategory(String segment) {
        String normalized = segment.toLowerCase(Locale.ROOT);
        return normalized.equals("술집")
                || normalized.equals("바")
                || normalized.equals("펍")
                || normalized.equals("클럽")
                || normalized.contains("주점")
                || normalized.contains("호프")
                || normalized.contains("생맥주")
                || normalized.contains("pub");
    }

    private static MealSuitability classifyKto(String sourceCategory) {
        String normalized = sourceCategory.strip().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("FD01") || normalized.startsWith("FD02")) {
            return MealSuitability.PREFERRED;
        }
        if (normalized.startsWith("FD03")) {
            return MealSuitability.SECONDARY;
        }
        if (normalized.startsWith("FD04")) {
            return MealSuitability.EXCLUDED;
        }
        return MealSuitability.UNKNOWN;
    }
}
