package com.runninggu.server.poi.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.poi.application.KtoPoiSource;
import com.runninggu.server.poi.application.PoiSearchCriteria;
import com.runninggu.server.poi.application.PoiSourceException;
import com.runninggu.server.poi.application.PoiSourceException.Reason;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import com.runninggu.server.poi.domain.PoiProvider;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** KTO 위치기반 관광·웰니스 정보를 서버 키로 호출한다. (SPEC §7.2·§8.1) */
public class KtoPoiClient implements KtoPoiSource {

    private static final Logger log = LoggerFactory.getLogger(KtoPoiClient.class);
    private static final String LOCATION_PATH = "/locationBasedList2";
    private static final String WELLNESS_LOCATION_PATH = "/locationBasedList";
    private static final String SUCCESS_CODE = "0000";
    private static final int PAGE_SIZE = 100;

    private final RestClient korRestClient;
    private final RestClient wellnessRestClient;
    private final ObjectMapper objectMapper;
    private final String serviceKey;

    public KtoPoiClient(
            RestClient korRestClient,
            RestClient wellnessRestClient,
            ObjectMapper objectMapper,
            String serviceKey) {
        this.korRestClient = korRestClient;
        this.wellnessRestClient = wellnessRestClient;
        this.objectMapper = objectMapper;
        this.serviceKey = serviceKey;
    }

    @Override
    public List<Poi> search(PoiSearchCriteria criteria, int limit) {
        if (!StringUtils.hasText(serviceKey)) {
            throw new PoiSourceException(Reason.ERROR);
        }
        String responseBody = fetch(criteria);
        return parse(responseBody, criteria).stream()
                .filter(poi -> matchesQuery(poi.name(), criteria.query()))
                .limit(limit)
                .toList();
    }

    private String fetch(PoiSearchCriteria criteria) {
        boolean wellness = criteria.category() == PoiCategory.WELLNESS;
        RestClient restClient = wellness ? wellnessRestClient : korRestClient;
        String path = wellness ? WELLNESS_LOCATION_PATH : LOCATION_PATH;
        try {
            return restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder
                                .path(path)
                                // URI 변수로 넘겨 '+' 등 디코딩 서비스 키 문자를 강제 인코딩한다.
                                .queryParam("serviceKey", "{serviceKey}")
                                .queryParam("numOfRows", PAGE_SIZE)
                                .queryParam("pageNo", 1)
                                .queryParam("MobileOS", "ETC")
                                .queryParam("MobileApp", "runninggu")
                                .queryParam("_type", "json")
                                .queryParam("mapX", criteria.lng().toPlainString())
                                .queryParam("mapY", criteria.lat().toPlainString())
                                .queryParam("radius", criteria.radius())
                                .queryParam("arrange", "E");
                        if (wellness) {
                            uriBuilder.queryParam("langDivCd", "KOR");
                        } else {
                            uriBuilder.queryParam(
                                    "contentTypeId",
                                    contentTypeId(criteria.category()));
                        }
                        return uriBuilder.build(serviceKey);
                    })
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException exception) {
            log.warn("KTO POI API가 HTTP 오류를 반환했습니다. status={}", exception.getStatusCode());
            throw new PoiSourceException(Reason.ERROR, exception);
        } catch (ResourceAccessException exception) {
            Reason reason = causedByTimeout(exception) ? Reason.TIMEOUT : Reason.ERROR;
            log.warn("KTO POI API 연결에 실패했습니다. reason={}", reason);
            throw new PoiSourceException(reason, exception);
        } catch (RestClientException exception) {
            log.warn("KTO POI API 응답을 처리하지 못했습니다.");
            throw new PoiSourceException(Reason.ERROR, exception);
        }
    }

    private List<Poi> parse(String responseBody, PoiSearchCriteria criteria) {
        if (!StringUtils.hasText(responseBody)) {
            throw new PoiSourceException(Reason.ERROR);
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root == null || !root.isObject()) {
                throw new PoiSourceException(Reason.ERROR);
            }
            JsonNode response = root.path("response");
            String resultCode = textOrNull(response.path("header").path("resultCode"));
            if (!SUCCESS_CODE.equals(resultCode)) {
                log.warn("KTO POI API가 실패 코드를 반환했습니다. resultCode={}", resultCode);
                throw new PoiSourceException(Reason.ERROR);
            }
            JsonNode body = response.path("body");
            int totalCount = body.path("totalCount").asInt(-1);
            if (totalCount < 0) {
                throw new PoiSourceException(Reason.ERROR);
            }
            List<JsonNode> rawItems = itemNodes(body.path("items").path("item"));
            List<Poi> items = rawItems.stream()
                    .map(item -> toPoi(item, criteria))
                    .flatMap(Optional::stream)
                    .toList();
            if (!rawItems.isEmpty() && items.isEmpty()) {
                throw new PoiSourceException(Reason.ERROR);
            }
            return items;
        } catch (JsonProcessingException exception) {
            log.warn("KTO POI API가 JSON이 아닌 응답을 반환했습니다.");
            throw new PoiSourceException(Reason.ERROR, exception);
        }
    }

    private List<JsonNode> itemNodes(JsonNode itemNode) {
        if (itemNode.isMissingNode() || itemNode.isNull() || itemNode.isTextual()) {
            return List.of();
        }
        if (itemNode.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            itemNode.forEach(items::add);
            return items;
        }
        return itemNode.isObject() ? List.of(itemNode) : List.of();
    }

    private Optional<Poi> toPoi(JsonNode item, PoiSearchCriteria criteria) {
        String name = textOrNull(item.path("title"));
        Coordinates coordinates = coordinatesOf(item);
        if (name == null || coordinates == null) {
            return Optional.empty();
        }
        String address = String.join(
                        " ",
                        textOrEmpty(item.path("addr1")),
                        textOrEmpty(item.path("addr2")))
                .strip();
        String description = address;
        if (criteria.category() == PoiCategory.WELLNESS) {
            String theme = textOrNull(item.path("wellnessThemaCd"));
            if (theme != null) {
                description = theme;
            }
        }
        return Optional.of(new Poi(
                name,
                criteria.category(),
                PoiProvider.KTO,
                coordinates.lat(),
                coordinates.lng(),
                distance(item.path("dist"), criteria, coordinates),
                description,
                address,
                "",
                firstTextOrNull(item, "firstimage", "firstImage")));
    }

    private Coordinates coordinatesOf(JsonNode item) {
        String lngText = firstTextOrNull(item, "mapx", "mapX");
        String latText = firstTextOrNull(item, "mapy", "mapY");
        if (latText == null || lngText == null) {
            return null;
        }
        try {
            BigDecimal lat = new BigDecimal(latText);
            BigDecimal lng = new BigDecimal(lngText);
            if (lat.compareTo(BigDecimal.valueOf(-90)) < 0
                    || lat.compareTo(BigDecimal.valueOf(90)) > 0
                    || lng.compareTo(BigDecimal.valueOf(-180)) < 0
                    || lng.compareTo(BigDecimal.valueOf(180)) > 0) {
                return null;
            }
            return new Coordinates(lat, lng);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private int distance(
            JsonNode distanceNode,
            PoiSearchCriteria criteria,
            Coordinates coordinates) {
        String value = textOrNull(distanceNode);
        if (value != null) {
            try {
                return Math.max(0, Math.toIntExact(Math.round(Double.parseDouble(value))));
            } catch (NumberFormatException ignored) {
                // 외부 거리값이 없거나 깨졌으면 WGS84 좌표로 계산한다.
            }
        }
        return DistanceCalculator.meters(
                criteria.lat(),
                criteria.lng(),
                coordinates.lat(),
                coordinates.lng());
    }

    private boolean matchesQuery(String name, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return normalize(name).contains(normalize(query));
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private int contentTypeId(PoiCategory category) {
        return switch (category) {
            case TOUR, HISTORY, NATURE -> 12;
            case FOOD -> 39;
            case LODGING -> 32;
            case CAFE, WELLNESS -> throw new IllegalArgumentException(
                    "KTO 위치기반 조회를 지원하지 않는 카테고리입니다: " + category);
        };
    }

    private String firstTextOrNull(JsonNode item, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = textOrNull(item.path(fieldName));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String textOrNull(JsonNode node) {
        if (!node.isValueNode()) {
            return null;
        }
        String value = node.asText().strip();
        return value.isEmpty() ? null : value;
    }

    private String textOrEmpty(JsonNode node) {
        String value = textOrNull(node);
        return value == null ? "" : value;
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

    private record Coordinates(BigDecimal lat, BigDecimal lng) {}
}
