package com.runninggu.server.festival.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.common.upstream.UpstreamEndpoint;
import com.runninggu.server.common.upstream.UpstreamLoadGuard;
import com.runninggu.server.festival.application.FestivalProvider;
import com.runninggu.server.festival.application.FestivalProviderException;
import com.runninggu.server.festival.application.FestivalProviderException.Reason;
import com.runninggu.server.festival.domain.Festival;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * KTO KorService2 searchFestival2·detailCommon2를 서버 키로 호출한다. (SPEC §7.2·§8.3)
 *
 * detailCommon2는 축제 공식 페이지(`homepage`) 하나를 얻으려고 건별로 부른다 — searchFestival2
 * 응답에는 그 값이 없다. 호출 수는 {@code FestivalOfficialUrlQuery} 의 contentId 별 캐시가 누른다.
 */
public class KtoFestivalClient implements FestivalProvider {

    private static final Logger log = LoggerFactory.getLogger(KtoFestivalClient.class);
    private static final String SEARCH_PATH = "/searchFestival2";
    private static final String DETAIL_PATH = "/detailCommon2";
    /** KTO `homepage` 는 `<a href="...">` HTML 조각으로 온다. 첫 href 만 쓴다. */
    private static final Pattern HOMEPAGE_HREF =
            Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final String SUCCESS_CODE = "0000";
    private static final int PAGE_SIZE = 1_000;
    private static final DateTimeFormatter KTO_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String serviceKey;
    private final UpstreamLoadGuard upstreamLoadGuard;

    public KtoFestivalClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            String serviceKey,
            UpstreamLoadGuard upstreamLoadGuard) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.serviceKey = serviceKey;
        this.upstreamLoadGuard = Objects.requireNonNull(
                upstreamLoadGuard,
                "upstreamLoadGuard");
    }

    @Override
    public List<Festival> searchStartingFrom(LocalDate eventStartDate) {
        if (!StringUtils.hasText(serviceKey)) {
            throw new FestivalProviderException(Reason.ERROR);
        }

        List<Festival> festivals = new ArrayList<>();
        int pageNo = 1;
        while (true) {
            SearchPage page = fetchPage(eventStartDate, pageNo);
            festivals.addAll(page.festivals());
            if ((long) pageNo * PAGE_SIZE >= page.totalCount()) {
                return List.copyOf(festivals);
            }
            if (page.rawItemCount() == 0) {
                throw new FestivalProviderException(Reason.ERROR);
            }
            pageNo++;
        }
    }

    @Override
    public Optional<String> findOfficialUrl(String contentId) {
        if (!StringUtils.hasText(serviceKey)) {
            throw new FestivalProviderException(Reason.ERROR);
        }
        String responseBody = call(
                UpstreamEndpoint.KTO_DETAIL_COMMON,
                uriBuilder -> uriBuilder
                        .path(DETAIL_PATH)
                        .queryParam("serviceKey", "{serviceKey}")
                        .queryParam("MobileOS", "ETC")
                        .queryParam("MobileApp", "runninggu")
                        .queryParam("_type", "json")
                        .queryParam("contentId", contentId)
                        .build(serviceKey));
        JsonNode body = parseSuccessBody(responseBody, UpstreamEndpoint.KTO_DETAIL_COMMON);
        return itemNodes(body.path("items").path("item")).stream()
                .map(item -> textOrNull(item.path("homepage")))
                .filter(Objects::nonNull)
                .map(KtoFestivalClient::extractHomepageUrl)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * `homepage` 에서 열 수 있는 주소 하나를 뽑는다.
     *
     * 실측 형태는 {@code <a href="http://..." target="_blank" title="새창 : 홈페이지">http://...</a>}
     * 다. 태그 없이 주소만 오는 경우도 있어 그때는 본문을 그대로 본다. **http·https 가 아니면
     * 버린다** — 앱이 Custom Tabs 로 여는 값이라 `javascript:` 같은 것을 서버에서 먼저 막는다.
     */
    static Optional<String> extractHomepageUrl(String homepage) {
        Matcher matcher = HOMEPAGE_HREF.matcher(homepage);
        String candidate = (matcher.find() ? matcher.group(1) : homepage).strip();
        if (candidate.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private SearchPage fetchPage(LocalDate eventStartDate, int pageNo) {
        String responseBody = call(
                UpstreamEndpoint.KTO_SEARCH_FESTIVAL,
                uriBuilder -> uriBuilder
                        .path(SEARCH_PATH)
                        // URI 변수로 넘겨 '+' 등 서비스 키 문자를 쿼리 값으로 강제 인코딩한다.
                        .queryParam("serviceKey", "{serviceKey}")
                        .queryParam("numOfRows", PAGE_SIZE)
                        .queryParam("pageNo", pageNo)
                        .queryParam("MobileOS", "ETC")
                        .queryParam("MobileApp", "runninggu")
                        .queryParam("_type", "json")
                        .queryParam("eventStartDate", eventStartDate.format(KTO_DATE))
                        .build(serviceKey));
        return parsePage(responseBody);
    }

    private String call(UpstreamEndpoint endpoint, Function<UriBuilder, URI> uri) {
        try {
            return restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException exception) {
            log.warn(
                    "KTO 축제 API가 HTTP 오류를 반환했습니다. endpoint={} status={}",
                    endpoint,
                    exception.getStatusCode());
            throw new FestivalProviderException(Reason.ERROR, exception);
        } catch (ResourceAccessException exception) {
            Reason reason = causedByTimeout(exception) ? Reason.TIMEOUT : Reason.ERROR;
            log.warn("KTO 축제 API 연결에 실패했습니다. endpoint={} reason={}", endpoint, reason);
            throw new FestivalProviderException(reason, exception);
        } catch (RestClientException exception) {
            log.warn("KTO 축제 API 응답을 처리하지 못했습니다. endpoint={}", endpoint);
            throw new FestivalProviderException(Reason.ERROR, exception);
        }
    }

    private SearchPage parsePage(String responseBody) {
        JsonNode body = parseSuccessBody(responseBody, UpstreamEndpoint.KTO_SEARCH_FESTIVAL);
        int totalCount = body.path("totalCount").asInt(-1);
        if (totalCount < 0) {
            throw new FestivalProviderException(Reason.ERROR);
        }

        List<JsonNode> rawItems = itemNodes(body.path("items").path("item"));
        List<Festival> festivals = rawItems.stream()
                .map(this::toFestival)
                .flatMap(Optional::stream)
                .toList();
        return new SearchPage(festivals, rawItems.size(), totalCount);
    }

    /** resultCode 0000 인 응답의 `body` 를 돌려준다. 판독 불가·실패 코드는 guard 를 trip 한다. */
    private JsonNode parseSuccessBody(String responseBody, UpstreamEndpoint endpoint) {
        if (!StringUtils.hasText(responseBody)) {
            tripKtoResultCode(endpoint);
            throw new FestivalProviderException(Reason.ERROR);
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root == null || !root.isObject()) {
                tripKtoResultCode(endpoint);
                throw new FestivalProviderException(Reason.ERROR);
            }
            JsonNode response = root.path("response");
            String resultCode = textOrNull(response.path("header").path("resultCode"));
            if (!SUCCESS_CODE.equals(resultCode)) {
                tripKtoResultCode(endpoint);
                log.warn("KTO 축제 API가 실패 코드를 반환했습니다. endpoint={}", endpoint);
                throw new FestivalProviderException(Reason.ERROR);
            }
            return response.path("body");
        } catch (JsonProcessingException exception) {
            tripKtoResultCode(endpoint);
            log.warn("KTO 축제 API가 JSON이 아닌 응답을 반환했습니다. endpoint={}", endpoint);
            throw new FestivalProviderException(Reason.ERROR, exception);
        }
    }

    private void tripKtoResultCode(UpstreamEndpoint endpoint) {
        upstreamLoadGuard.tripKtoResultCode(endpoint);
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

    private Optional<Festival> toFestival(JsonNode item) {
        String contentId = textOrNull(item.path("contentid"));
        String name = textOrNull(item.path("title"));
        String startDateText = textOrNull(item.path("eventstartdate"));
        String endDateText = textOrNull(item.path("eventenddate"));
        if (contentId == null
                || name == null
                || startDateText == null
                || endDateText == null) {
            return Optional.empty();
        }

        try {
            LocalDate startDate = LocalDate.parse(startDateText, KTO_DATE);
            LocalDate endDate = LocalDate.parse(endDateText, KTO_DATE);
            if (endDate.isBefore(startDate)) {
                return Optional.empty();
            }
            Coordinates coordinates = coordinatesOf(item);
            return Optional.of(new Festival(
                    contentId,
                    name,
                    startDate,
                    endDate,
                    coordinates.lat(),
                    coordinates.lng(),
                    textOrNull(item.path("firstimage")),
                    textOrEmpty(item.path("addr1"))));
        } catch (DateTimeException exception) {
            return Optional.empty();
        }
    }

    private Coordinates coordinatesOf(JsonNode item) {
        String lngText = textOrNull(item.path("mapx"));
        String latText = textOrNull(item.path("mapy"));
        if (latText == null || lngText == null) {
            return Coordinates.missing();
        }
        try {
            BigDecimal lat = new BigDecimal(latText);
            BigDecimal lng = new BigDecimal(lngText);
            return validCoordinates(lat, lng)
                    ? new Coordinates(lat, lng)
                    : Coordinates.missing();
        } catch (NumberFormatException exception) {
            return Coordinates.missing();
        }
    }

    private boolean validCoordinates(BigDecimal lat, BigDecimal lng) {
        return lat.compareTo(BigDecimal.valueOf(-90)) >= 0
                && lat.compareTo(BigDecimal.valueOf(90)) <= 0
                && lng.compareTo(BigDecimal.valueOf(-180)) >= 0
                && lng.compareTo(BigDecimal.valueOf(180)) <= 0;
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

    private record SearchPage(
            List<Festival> festivals,
            int rawItemCount,
            int totalCount) {}

    private record Coordinates(BigDecimal lat, BigDecimal lng) {

        private static Coordinates missing() {
            return new Coordinates(null, null);
        }
    }
}
