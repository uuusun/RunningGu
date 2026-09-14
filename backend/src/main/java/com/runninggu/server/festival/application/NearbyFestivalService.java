package com.runninggu.server.festival.application;

import com.runninggu.server.festival.domain.NearbyFestival;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 대회 인근 축제 응답. (API 명세 §3-5)
 *
 * 기본 목록(거리순 6건)은 {@link CachedNearbyFestivalQuery} 가 하루 캐시하고, 공식 페이지는
 * **캐시 바깥·반환 직전에** 붙인다 — 홈({@link HomeFestivalService})과 같은 구조다.
 * 그래야 링크 조회가 한 번 실패해도 다음 요청이 contentId 캐시를 다시 지나며 재시도한다(#352 리뷰).
 */
@Service
public class NearbyFestivalService {

    private final CachedNearbyFestivalQuery cachedQuery;
    private final FestivalOfficialUrlQuery officialUrlQuery;

    public NearbyFestivalService(
            CachedNearbyFestivalQuery cachedQuery,
            FestivalOfficialUrlQuery officialUrlQuery) {
        this.cachedQuery = cachedQuery;
        this.officialUrlQuery = officialUrlQuery;
    }

    public List<NearbyFestival> findNearby(long contestId) {
        List<NearbyFestival> festivals = cachedQuery.findNearby(contestId);
        // 여섯 건으로 잘린 목록에만 붙인다 — 한꺼번에 부르고 전체 예산 안에 끝낸다 (§3-5)
        Map<String, String> officialUrls = officialUrlQuery.findAll(
                festivals.stream().map(NearbyFestival::contentId).toList());
        return festivals.stream()
                .map(festival -> festival.withOfficialUrl(officialUrls.get(festival.contentId())))
                .toList();
    }
}
