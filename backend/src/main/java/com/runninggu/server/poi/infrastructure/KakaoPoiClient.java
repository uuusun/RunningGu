package com.runninggu.server.poi.infrastructure;

import com.runninggu.server.poi.application.KakaoPoiSource;
import com.runninggu.server.poi.application.PoiSearchCriteria;
import com.runninggu.server.poi.application.PoiSourceException;
import com.runninggu.server.poi.application.PoiSourceException.Reason;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import com.runninggu.server.poi.domain.PoiProvider;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 카카오 로컬 카테고리·키워드 검색을 서버 키로 호출한다. (SPEC §5.3·§7.3) */
public class KakaoPoiClient implements KakaoPoiSource {

    private static final Logger log = LoggerFactory.getLogger(KakaoPoiClient.class);
    private static final String CATEGORY_PATH = "/v2/local/search/category.json";
    private static final String KEYWORD_PATH = "/v2/local/search/keyword.json";
    private static final int PAGE_SIZE = 15;
    private static final int MAX_ATTEMPTS = 2;

    private final RestClient restClient;
    private final String restKey;

    public KakaoPoiClient(RestClient restClient, String restKey) {
        this.restClient = restClient;
        this.restKey = restKey;
    }

    @Override
    public List<Poi> search(PoiSearchCriteria criteria, int limit) {
        if (!StringUtils.hasText(restKey)) {
            throw new PoiSourceException(Reason.ERROR);
        }

        SearchMode mode = searchMode(criteria);
        List<Poi> result = new ArrayList<>();
        int page = 1;
        boolean end = false;
        while (result.size() < limit && !end) {
            int requestedSize = Math.min(PAGE_SIZE, limit - result.size());
            KakaoPoiSearchResponse response = executeWithRateLimitRetry(
                    criteria,
                    mode,
                    page,
                    requestedSize);
            List<KakaoPoiSearchResponse.Document> documents = response.documents() == null
                    ? List.of()
                    : response.documents();
            int mappedCount = 0;
            for (KakaoPoiSearchResponse.Document document : documents) {
                Optional<Poi> poi = toPoi(document, criteria);
                if (poi.isPresent()) {
                    result.add(poi.get());
                    mappedCount++;
                }
            }
            if (!documents.isEmpty() && mappedCount == 0) {
                throw new PoiSourceException(Reason.ERROR);
            }
            end = response.meta() == null
                    || Boolean.TRUE.equals(response.meta().isEnd())
                    || documents.size() < requestedSize;
            page++;
        }
        return result.stream().limit(limit).toList();
    }

    private KakaoPoiSearchResponse executeWithRateLimitRetry(
            PoiSearchCriteria criteria,
            SearchMode mode,
            int page,
            int size) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                KakaoPoiSearchResponse response = restClient.get()
                        .uri(uriBuilder -> {
                            uriBuilder.path(mode.keyword() == null ? CATEGORY_PATH : KEYWORD_PATH);
                            if (mode.keyword() != null) {
                                uriBuilder.queryParam("query", mode.keyword());
                            }
                            if (mode.categoryGroupCode() != null) {
                                uriBuilder.queryParam(
                                        "category_group_code",
                                        mode.categoryGroupCode());
                            }
                            return uriBuilder
                                    .queryParam("x", criteria.lng().toPlainString())
                                    .queryParam("y", criteria.lat().toPlainString())
                                    .queryParam("radius", criteria.radius())
                                    .queryParam("page", page)
                                    .queryParam("size", size)
                                    .queryParam("sort", "distance")
                                    .build();
                        })
                        .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restKey)
                        .retrieve()
                        .body(KakaoPoiSearchResponse.class);
                if (response == null) {
                    throw new PoiSourceException(Reason.ERROR);
                }
                if (response.meta() == null
                        || response.meta().isEnd() == null
                        || response.documents() == null) {
                    throw new PoiSourceException(Reason.ERROR);
                }
                return response;
            } catch (RestClientResponseException exception) {
                if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                        && attempt < MAX_ATTEMPTS) {
                    continue;
                }
                log.warn("카카오 POI API가 HTTP 오류를 반환했습니다. status={}", exception.getStatusCode());
                throw new PoiSourceException(Reason.ERROR, exception);
            } catch (ResourceAccessException exception) {
                Reason reason = causedByTimeout(exception) ? Reason.TIMEOUT : Reason.ERROR;
                log.warn("카카오 POI API 연결에 실패했습니다. reason={}", reason);
                throw new PoiSourceException(reason, exception);
            } catch (RestClientException exception) {
                log.warn("카카오 POI API 응답을 처리하지 못했습니다.");
                throw new PoiSourceException(Reason.ERROR, exception);
            }
        }
        throw new PoiSourceException(Reason.ERROR);
    }

    private SearchMode searchMode(PoiSearchCriteria criteria) {
        String groupCode = categoryGroupCode(criteria.category());
        if (criteria.hasQuery()) {
            return new SearchMode(criteria.query(), groupCode);
        }
        return switch (criteria.category()) {
            case HISTORY -> new SearchMode("박물관 유적지 문화재", null);
            case WELLNESS -> new SearchMode("온천 스파 사우나 찜질방", null);
            case NATURE -> new SearchMode("둘레길 공원 산책로 수목원", null);
            case TOUR, FOOD, CAFE, LODGING -> new SearchMode(null, groupCode);
        };
    }

    private String categoryGroupCode(PoiCategory category) {
        return switch (category) {
            case TOUR -> "AT4";
            case FOOD -> "FD6";
            case CAFE -> "CE7";
            case LODGING -> "AD5";
            case WELLNESS, NATURE, HISTORY -> null;
        };
    }

    private Optional<Poi> toPoi(
            KakaoPoiSearchResponse.Document document,
            PoiSearchCriteria criteria) {
        String name = textOrNull(document.placeName());
        if (name == null) {
            return Optional.empty();
        }
        try {
            String lngText = textOrNull(document.x());
            String latText = textOrNull(document.y());
            if (lngText == null || latText == null) {
                return Optional.empty();
            }
            BigDecimal lng = new BigDecimal(lngText);
            BigDecimal lat = new BigDecimal(latText);
            if (!validCoordinates(lat, lng)) {
                return Optional.empty();
            }
            String address = textOrNull(document.roadAddressName());
            if (address == null) {
                address = textOrNull(document.addressName());
            }
            return Optional.of(new Poi(
                    name,
                    criteria.category(),
                    PoiProvider.KAKAO,
                    lat,
                    lng,
                    distance(document.distance(), criteria, lat, lng),
                    textOrEmpty(document.categoryName()),
                    address == null ? "" : address,
                    textOrEmpty(document.placeUrl()),
                    null));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private int distance(
            String rawDistance,
            PoiSearchCriteria criteria,
            BigDecimal lat,
            BigDecimal lng) {
        String value = textOrNull(rawDistance);
        if (value != null) {
            try {
                return Math.max(0, Math.toIntExact(Math.round(Double.parseDouble(value))));
            } catch (NumberFormatException ignored) {
                // 외부 거리값이 없거나 깨졌으면 WGS84 좌표로 계산한다.
            }
        }
        return DistanceCalculator.meters(criteria.lat(), criteria.lng(), lat, lng);
    }

    private boolean validCoordinates(BigDecimal lat, BigDecimal lng) {
        return lat.compareTo(BigDecimal.valueOf(-90)) >= 0
                && lat.compareTo(BigDecimal.valueOf(90)) <= 0
                && lng.compareTo(BigDecimal.valueOf(-180)) >= 0
                && lng.compareTo(BigDecimal.valueOf(180)) <= 0;
    }

    private String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private String textOrEmpty(String value) {
        String normalized = textOrNull(value);
        return normalized == null ? "" : normalized;
    }

    private boolean causedByTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record SearchMode(String keyword, String categoryGroupCode) {}
}
