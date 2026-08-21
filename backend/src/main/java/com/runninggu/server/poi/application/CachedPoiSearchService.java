package com.runninggu.server.poi.application;

import com.runninggu.server.common.config.CacheConfig;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.poi.application.PoiSourceException.Reason;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class CachedPoiSearchService {

    private static final Logger log = LoggerFactory.getLogger(CachedPoiSearchService.class);
    private static final int EXPANDED_RADIUS = 20_000;
    private static final int EXPANSION_THRESHOLD = 3;

    private final KakaoPoiSource kakaoSource;
    private final KtoPoiSource ktoSource;
    private final List<NaturePoiBundleSource> natureBundleSources;

    public CachedPoiSearchService(
            KakaoPoiSource kakaoSource,
            KtoPoiSource ktoSource,
            List<NaturePoiBundleSource> natureBundleSources) {
        this.kakaoSource = kakaoSource;
        this.ktoSource = ktoSource;
        this.natureBundleSources = List.copyOf(natureBundleSources);
    }

    /** 카테고리별 실시간 원천을 조회하고 성공 응답만 5분간 캐시한다. (SPEC 결정-49) */
    @Cacheable(
            cacheNames = CacheConfig.POI_CACHE,
            key = "#criteria.cacheKey()",
            sync = true)
    public PoiSearchResult search(PoiSearchCriteria criteria) {
        List<Reason> failures = new ArrayList<>();
        Source primarySource = primarySource(criteria.category());
        int primarySearchLimit = Math.max(criteria.size(), EXPANSION_THRESHOLD);

        Attempt primary = attempt(primarySource, criteria, primarySearchLimit);
        primary.failureReason().ifPresent(failures::add);
        List<Poi> primaryItems = new ArrayList<>(primary.items());
        addNatureBundleItems(criteria, primarySearchLimit, primaryItems);

        int effectiveRadius = criteria.radius();
        if (primary.succeeded()
                && deduplicate(primaryItems).size() < EXPANSION_THRESHOLD
                && criteria.radius() < EXPANDED_RADIUS) {
            PoiSearchCriteria expandedCriteria = criteria.withRadius(EXPANDED_RADIUS);
            Attempt expanded = attempt(primarySource, expandedCriteria, primarySearchLimit);
            expanded.failureReason().ifPresent(failures::add);
            if (expanded.succeeded()) {
                primaryItems.addAll(expanded.items());
                addNatureBundleItems(expandedCriteria, primarySearchLimit, primaryItems);
                effectiveRadius = EXPANDED_RADIUS;
            }
        }

        List<Poi> combined = new ArrayList<>(primaryItems);
        Source fallbackSource = fallbackSource(criteria.category());
        if (fallbackSource != null && deduplicate(combined).size() < criteria.size()) {
            PoiSearchCriteria fallbackCriteria = criteria.withRadius(effectiveRadius);
            Attempt fallback = attempt(fallbackSource, fallbackCriteria, criteria.size());
            fallback.failureReason().ifPresent(failures::add);
            combined.addAll(fallback.items());
        }

        List<Poi> items = deduplicate(combined).stream()
                .sorted(Comparator.comparingInt(Poi::distanceM))
                .limit(criteria.size())
                .toList();
        if (!items.isEmpty() || failures.isEmpty()) {
            return new PoiSearchResult(items);
        }
        if (failures.stream().allMatch(reason -> reason == Reason.TIMEOUT)) {
            throw new ApiException(
                    ErrorCode.EXTERNAL_API_TIMEOUT,
                    "주변 장소 검색 응답 시간이 초과됐습니다.");
        }
        throw new ApiException(
                ErrorCode.EXTERNAL_API_ERROR,
                "주변 장소 검색을 완료하지 못했습니다.");
    }

    private Attempt attempt(
            Source source,
            PoiSearchCriteria criteria,
            int limit) {
        try {
            List<Poi> items = source.search().apply(criteria, limit);
            return Attempt.success(items == null ? List.of() : items);
        } catch (PoiSourceException exception) {
            return Attempt.failure(exception.reason());
        }
    }

    private void addNatureBundleItems(
            PoiSearchCriteria criteria,
            int limit,
            List<Poi> destination) {
        if (criteria.category() != PoiCategory.NATURE) {
            return;
        }
        for (NaturePoiBundleSource source : natureBundleSources) {
            try {
                List<Poi> items = source.search(criteria, limit);
                if (items != null) {
                    destination.addAll(items);
                }
            } catch (RuntimeException exception) {
                // AP-23 번들 장애는 카카오·KTO 실시간 결과를 막지 않는다. (결정-49)
                log.warn("두루누비 NATURE 후보 연결에 실패해 해당 번들만 제외합니다.");
            }
        }
    }

    private List<Poi> deduplicate(List<Poi> items) {
        LinkedHashMap<String, Poi> unique = new LinkedHashMap<>();
        for (Poi item : items) {
            unique.putIfAbsent(deduplicationKey(item), item);
        }
        return List.copyOf(unique.values());
    }

    private String deduplicationKey(Poi poi) {
        return normalizeName(poi.name())
                + "|"
                + coordinateKey(poi.lat())
                + "|"
                + coordinateKey(poi.lng());
    }

    private String normalizeName(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private String coordinateKey(BigDecimal coordinate) {
        return coordinate.stripTrailingZeros().toPlainString();
    }

    private Source primarySource(PoiCategory category) {
        return switch (category) {
            case TOUR, HISTORY, WELLNESS -> new Source(ktoSource::search);
            case FOOD, CAFE, NATURE, LODGING -> new Source(kakaoSource::search);
        };
    }

    private Source fallbackSource(PoiCategory category) {
        return switch (category) {
            case TOUR, HISTORY, WELLNESS -> new Source(kakaoSource::search);
            case FOOD, NATURE, LODGING -> new Source(ktoSource::search);
            case CAFE -> null;
        };
    }

    private record Source(BiFunction<PoiSearchCriteria, Integer, List<Poi>> search) {}

    private record Attempt(List<Poi> items, Reason failure) {

        private static Attempt success(List<Poi> items) {
            return new Attempt(List.copyOf(items), null);
        }

        private static Attempt failure(Reason reason) {
            return new Attempt(List.of(), reason);
        }

        private boolean succeeded() {
            return failure == null;
        }

        private java.util.Optional<Reason> failureReason() {
            return java.util.Optional.ofNullable(failure);
        }
    }
}
