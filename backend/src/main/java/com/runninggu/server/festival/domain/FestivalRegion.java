package com.runninggu.server.festival.domain;

import java.util.Optional;

/** KTO 주소를 앱이 함께 쓰는 17개 시도 단축명으로 정규화한다. (API 명세 §4-1) */
public final class FestivalRegion {

    private FestivalRegion() {}

    public static Optional<String> fromAddress(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        String firstToken = address.strip().split("\\s+", 2)[0];
        return Optional.ofNullable(switch (firstToken) {
            case "서울", "서울특별시" -> "서울";
            case "부산", "부산광역시" -> "부산";
            case "대구", "대구광역시" -> "대구";
            case "인천", "인천광역시" -> "인천";
            case "광주", "광주광역시" -> "광주";
            case "대전", "대전광역시" -> "대전";
            case "울산", "울산광역시" -> "울산";
            case "세종", "세종특별자치시" -> "세종";
            case "경기", "경기도" -> "경기";
            case "강원", "강원도", "강원특별자치도" -> "강원";
            case "충북", "충청북도" -> "충북";
            case "충남", "충청남도" -> "충남";
            case "전북", "전라북도", "전북특별자치도" -> "전북";
            case "전남", "전라남도" -> "전남";
            case "경북", "경상북도" -> "경북";
            case "경남", "경상남도" -> "경남";
            case "제주", "제주도", "제주특별자치도" -> "제주";
            default -> null;
        });
    }
}
