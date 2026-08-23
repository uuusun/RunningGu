package com.runninggu.server.itinerary.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.itinerary.domain.ItineraryPlace;
import com.runninggu.server.itinerary.domain.PoiPools;
import com.runninggu.server.poi.application.PoiService;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 카테고리별 POI를 동시에 적재하고 외부 장애를 해당 풀의 빈 결과로 격리한다. (SPEC §5.6-3, NFR-3)
 */
@Component
public class ItineraryPoiPoolLoader {

    static final int SEARCH_RADIUS_M = 8_000;
    static final int SEARCH_SIZE = 8;
    private static final Logger log = LoggerFactory.getLogger(ItineraryPoiPoolLoader.class);

    private final PoiService poiService;

    public ItineraryPoiPoolLoader(PoiService poiService) {
        this.poiService = poiService;
    }

    public PoiPools load(
            List<PoiCategory> categories,
            BigDecimal lat,
            BigDecimal lng) {
        LinkedHashMap<PoiCategory, CompletableFuture<LoadedCategory>> futures =
                new LinkedHashMap<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (PoiCategory category : categories) {
                futures.put(category, CompletableFuture.supplyAsync(
                        () -> loadCategory(category, lat, lng),
                        executor));
            }

            LinkedHashMap<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>();
            LinkedHashMap<PoiCategory, String> sources = new LinkedHashMap<>();
            futures.forEach((category, future) -> {
                LoadedCategory loaded = join(future);
                places.put(category, loaded.places());
                if (loaded.completed()) {
                    sources.put(category, "LIVE");
                }
            });
            return new PoiPools(places, sources);
        }
    }

    private LoadedCategory loadCategory(
            PoiCategory category,
            BigDecimal lat,
            BigDecimal lng) {
        try {
            List<ItineraryPlace> places = poiService.search(
                            category,
                            lat,
                            lng,
                            SEARCH_RADIUS_M,
                            null,
                            SEARCH_SIZE)
                    .items()
                    .stream()
                    .map(this::toPlace)
                    .toList();
            return new LoadedCategory(places, true);
        } catch (ApiException exception) {
            if (exception.errorCode() != ErrorCode.EXTERNAL_API_ERROR
                    && exception.errorCode() != ErrorCode.EXTERNAL_API_TIMEOUT) {
                throw exception;
            }
            // 사용자 좌표는 로그에 남기지 않는다. (AGENTS 8장)
            log.warn(
                    "동선 POI 원천 장애로 카테고리 풀을 비웁니다. category={}, code={}",
                    category,
                    exception.errorCode());
            return new LoadedCategory(List.of(), false);
        }
    }

    private ItineraryPlace toPlace(Poi poi) {
        return new ItineraryPlace(
                poi.name(),
                poi.address(),
                poi.lat(),
                poi.lng(),
                poi.description());
    }

    private LoadedCategory join(CompletableFuture<LoadedCategory> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private record LoadedCategory(List<ItineraryPlace> places, boolean completed) {

        private LoadedCategory {
            places = List.copyOf(places);
        }
    }
}
