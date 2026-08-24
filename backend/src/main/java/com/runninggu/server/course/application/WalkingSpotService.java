package com.runninggu.server.course.application;

import com.runninggu.server.common.config.CacheConfig;
import com.runninggu.server.poi.application.KakaoPoiSource;
import com.runninggu.server.poi.application.PoiSearchCriteria;
import com.runninggu.server.poi.application.PoiSourceException;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** 카카오 걷기 장소를 시설 제외·공원 묶기 규칙으로 정제한다. (SPEC §5.9) */
@Service
public class WalkingSpotService {

    private static final List<String> KEYWORDS =
            List.of("공원", "산책로", "둘레길", "하천", "한강공원", "생태공원");
    private static final int RADIUS_M = 3_000;
    private static final int QUERY_LIMIT = 15;
    private static final int RESULT_LIMIT = 12;
    private static final Pattern INCLUDED_CATEGORY = Pattern.compile(
            "공원|관광|명소|산책|둘레|하천|유원지|수목원|숲|생태|휴양|호수|해수욕|해변|등산로|트레킹|자연");
    private static final Pattern EXCLUDED_CATEGORY = Pattern.compile(
            "공원시설물|공원관리운영|공공기관|사무소");
    private static final Pattern EXCLUDED_NAME = Pattern.compile(
            "화장실|주차장|주차|테니스|풋살|축구장|야구장|농구장|체육관|관리사무소|매점|안내소|정류장|어린이공원|놀이공원|쌈지공원|소공원");

    private final KakaoPoiSource kakaoPoiSource;

    public WalkingSpotService(KakaoPoiSource kakaoPoiSource) {
        this.kakaoPoiSource = kakaoPoiSource;
    }

    @Cacheable(
            cacheNames = CacheConfig.WALKING_SPOTS_CACHE,
            key = "#lat.toPlainString() + '|' + #lng.toPlainString()",
            unless = "#result.degraded()")
    public WalkingSpotSearchResult search(BigDecimal lat, BigDecimal lng) {
        List<Poi> candidates = new ArrayList<>();
        boolean degraded = false;
        for (String keyword : KEYWORDS) {
            try {
                List<Poi> found = kakaoPoiSource.search(
                        new PoiSearchCriteria(
                                PoiCategory.NATURE,
                                lat,
                                lng,
                                RADIUS_M,
                                keyword,
                                QUERY_LIMIT),
                        QUERY_LIMIT);
                if (found != null) {
                    candidates.addAll(found);
                }
            } catch (PoiSourceException exception) {
                degraded = true;
            }
        }
        return new WalkingSpotSearchResult(refine(candidates), degraded);
    }

    private List<WalkingSpot> refine(List<Poi> candidates) {
        LinkedHashMap<String, Poi> byNameAndAddress = new LinkedHashMap<>();
        candidates.stream()
                .filter(this::isWalkable)
                .sorted(Comparator.comparingInt(Poi::distanceM))
                .forEach(poi -> byNameAndAddress.putIfAbsent(
                        normalized(poi.name()) + '|' + normalized(poi.address()),
                        poi));

        LinkedHashMap<String, Poi> byFirstWord = new LinkedHashMap<>();
        for (Poi candidate : byNameAndAddress.values()) {
            String firstWord = firstWord(candidate.name());
            byFirstWord.compute(firstWord, (key, current) -> representative(
                    firstWord,
                    current,
                    candidate));
        }
        return byFirstWord.values().stream()
                .sorted(Comparator.comparingInt(Poi::distanceM)
                        .thenComparing(Poi::name))
                .limit(RESULT_LIMIT)
                .map(this::toWalkingSpot)
                .toList();
    }

    private boolean isWalkable(Poi poi) {
        String category = poi.description();
        return INCLUDED_CATEGORY.matcher(category).find()
                && !EXCLUDED_CATEGORY.matcher(category).find()
                && !EXCLUDED_NAME.matcher(poi.name()).find();
    }

    private Poi representative(String firstWord, Poi current, Poi candidate) {
        if (current == null) {
            return candidate;
        }
        boolean currentExact = normalized(current.name()).equals(firstWord);
        boolean candidateExact = normalized(candidate.name()).equals(firstWord);
        if (candidateExact != currentExact) {
            return candidateExact ? candidate : current;
        }
        return candidate.distanceM() < current.distanceM() ? candidate : current;
    }

    private WalkingSpot toWalkingSpot(Poi poi) {
        String[] categoryParts = poi.description().split(">");
        String category = categoryParts.length == 0
                ? poi.description()
                : categoryParts[categoryParts.length - 1].strip();
        return new WalkingSpot(
                poi.name(),
                poi.distanceM(),
                poi.lat(),
                poi.lng(),
                category,
                poi.address(),
                poi.url());
    }

    private String firstWord(String name) {
        String normalized = normalized(name);
        int whitespace = normalized.indexOf(' ');
        return whitespace < 0 ? normalized : normalized.substring(0, whitespace);
    }

    private String normalized(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }
}
