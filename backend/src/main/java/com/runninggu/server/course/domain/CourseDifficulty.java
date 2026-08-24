package com.runninggu.server.course.domain;

/** 지역별 목록에 표시하는 전체 원본 코스 기준 난이도다. (SPEC §5.8) */
public enum CourseDifficulty {
    EASY,
    NORMAL,
    HARD;

    public static CourseDifficulty fromKtoLevel(String level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case "1" -> EASY;
            case "2" -> NORMAL;
            case "3" -> HARD;
            default -> null;
        };
    }
}
