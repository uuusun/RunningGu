package com.runninggu.server.itinerary.domain;

import com.runninggu.server.contest.domain.ContestEventType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** SPEC §5.1의 종목별 회복 값을 한 곳에서 제공한다. */
public final class RecoveryPolicy {

    private static final Map<ContestEventType, RecoveryRule> RULES = rules();

    private RecoveryPolicy() {}

    public static RecoveryRule forEvent(ContestEventType event) {
        return RULES.get(event);
    }

    public static GeneratedRecovery recoveryFor(
            ContestEventType event,
            List<Integer> dayOffsets) {
        RecoveryRule rule = forEvent(event);
        if (!rule.noHard()) {
            return null;
        }
        Integer firstPlus = dayOffsets.stream()
                .filter(offset -> offset > 0)
                .min(Integer::compareTo)
                .orElse(null);
        if (firstPlus != null) {
            return new GeneratedRecovery(dayLabel(firstPlus) + " 회복 모드", rule.dplus());
        }
        return new GeneratedRecovery("D-day 회복 모드", rule.dday());
    }

    /** HALF/FULL의 D+가 회복일이며 D+가 없는 일정은 D-day를 회복일로 쓴다. (결정-53) */
    public static boolean isRecoveryDay(
            ContestEventType event,
            int dayOffset,
            boolean hasPlusDay) {
        RecoveryRule rule = forEvent(event);
        return rule.noHard()
                && (dayOffset > 0 || (!hasPlusDay && dayOffset == 0));
    }

    public static String dayLabel(int offset) {
        if (offset == 0) {
            return "D-day";
        }
        return offset > 0 ? "D+" + offset : "D" + offset;
    }

    private static Map<ContestEventType, RecoveryRule> rules() {
        EnumMap<ContestEventType, RecoveryRule> rules =
                new EnumMap<>(ContestEventType.class);
        rules.put(ContestEventType.K5, new RecoveryRule(
                false,
                "거의 정상",
                "완주 후 오후부터 자유 관광",
                "일반 관광 자유"));
        rules.put(ContestEventType.K10, new RecoveryRule(
                false,
                "낮은 피로",
                "완주 후 가벼운 관광·축제",
                "일반 관광"));
        rules.put(ContestEventType.HALF, new RecoveryRule(
                true,
                "중등도 피로",
                "완주 후 온천·휴식 권장",
                "온천+짧은 산책(고강도 제외)"));
        rules.put(ContestEventType.FULL, new RecoveryRule(
                true,
                "고강도 회복 필요",
                "완주 후 회복 집중, 도보 최소",
                "스파·온천 중심, 도보 최소"));
        return Map.copyOf(rules);
    }
}
