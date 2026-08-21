package com.runninggu.server.festival.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FestivalRegionTest {

    @Test
    void 주소의_첫_토큰을_17개_시도_단축명으로_정규화한다() {
        Map<String, String> addresses = Map.ofEntries(
                Map.entry("서울특별시 종로구", "서울"),
                Map.entry("부산광역시 해운대구", "부산"),
                Map.entry("대구광역시 중구", "대구"),
                Map.entry("인천광역시 남동구", "인천"),
                Map.entry("광주광역시 북구", "광주"),
                Map.entry("대전광역시 유성구", "대전"),
                Map.entry("울산광역시 남구", "울산"),
                Map.entry("세종특별자치시 나성동", "세종"),
                Map.entry("경기도 수원시", "경기"),
                Map.entry("강원특별자치도 춘천시", "강원"),
                Map.entry("충청북도 청주시", "충북"),
                Map.entry("충청남도 천안시", "충남"),
                Map.entry("전북특별자치도 전주시", "전북"),
                Map.entry("전라남도 여수시", "전남"),
                Map.entry("경상북도 경주시", "경북"),
                Map.entry("경상남도 창원시", "경남"),
                Map.entry("제주특별자치도 제주시", "제주"));

        addresses.forEach((address, expected) ->
                assertThat(FestivalRegion.fromAddress(address)).contains(expected));
    }

    @Test
    void 주소가_없거나_시도를_판별할_수_없으면_비어_있다() {
        assertThat(FestivalRegion.fromAddress(null)).isEmpty();
        assertThat(FestivalRegion.fromAddress("   ")).isEmpty();
        assertThat(FestivalRegion.fromAddress("알수없는지역 행사장")).isEmpty();
    }
}
