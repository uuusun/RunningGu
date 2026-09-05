package com.runninggu.server.course.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.common.upstream.UpstreamEndpoint;
import com.runninggu.server.common.upstream.UpstreamLoadGuard;
import com.runninggu.server.course.application.CourseMetadata;
import com.runninggu.server.course.application.CourseMetadataBatch;
import com.runninggu.server.course.application.CourseMetadataProvider;
import com.runninggu.server.course.application.CourseMetadataSyncException;
import com.runninggu.server.course.application.CourseMetadataSyncException.Reason;
import com.runninggu.server.course.domain.CourseDifficulty;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 두루누비 courseList 전체 페이지를 한 번의 메타 snapshot으로 수집한다. (SPEC §8.4) */
public final class KtoCourseClient implements CourseMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(KtoCourseClient.class);
    private static final String COURSE_LIST_PATH = "/courseList";
    private static final String SUCCESS_CODE = "0000";
    private static final int PAGE_SIZE = 100;
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]+");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String serviceKey;
    private final UpstreamLoadGuard upstreamLoadGuard;

    public KtoCourseClient(
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
    public CourseMetadataBatch fetchAll() {
        if (!StringUtils.hasText(serviceKey)) {
            throw new CourseMetadataSyncException(Reason.MISSING_KEY);
        }

        Map<String, CourseMetadata> metadata = new LinkedHashMap<>();
        int pageNo = 1;
        int expectedTotal = -1;
        int rawCount = 0;
        int invalidFieldCount = 0;
        while (true) {
            CourseListPage page = fetchPage(pageNo);
            if (expectedTotal < 0) {
                expectedTotal = page.totalCount();
                if (expectedTotal <= 0) {
                    throw new CourseMetadataSyncException(Reason.INVALID_RESPONSE);
                }
            } else if (expectedTotal != page.totalCount()) {
                throw new CourseMetadataSyncException(Reason.INVALID_RESPONSE);
            }
            if (page.items().isEmpty() && rawCount < expectedTotal) {
                throw new CourseMetadataSyncException(Reason.INVALID_RESPONSE);
            }

            for (JsonNode item : page.items()) {
                ParsedMetadata parsed = parseMetadata(item);
                if (metadata.putIfAbsent(parsed.metadata().courseId(), parsed.metadata()) != null) {
                    throw new CourseMetadataSyncException(Reason.INVALID_RESPONSE);
                }
                invalidFieldCount += parsed.invalidFieldCount();
            }
            rawCount += page.items().size();
            if (rawCount >= expectedTotal) {
                if (rawCount != expectedTotal) {
                    throw new CourseMetadataSyncException(Reason.INVALID_RESPONSE);
                }
                return new CourseMetadataBatch(metadata, rawCount, invalidFieldCount);
            }
            pageNo++;
        }
    }

    private CourseListPage fetchPage(int pageNo) {
        String responseBody;
        try {
            responseBody = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(COURSE_LIST_PATH)
                            .queryParam("serviceKey", "{serviceKey}")
                            .queryParam("pageNo", pageNo)
                            .queryParam("numOfRows", PAGE_SIZE)
                            .queryParam("MobileOS", "ETC")
                            .queryParam("MobileApp", "RunningGu")
                            .queryParam("brdDiv", "DNWW")
                            .queryParam("_type", "json")
                            .build(serviceKey))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException exception) {
            log.warn("두루누비 API가 HTTP 오류를 반환했습니다. status={}", exception.getStatusCode());
            throw new CourseMetadataSyncException(Reason.HTTP_ERROR, exception);
        } catch (ResourceAccessException exception) {
            Reason reason = causedByTimeout(exception) ? Reason.TIMEOUT : Reason.HTTP_ERROR;
            log.warn("두루누비 API 연결에 실패했습니다. reason={}", reason);
            throw new CourseMetadataSyncException(reason, exception);
        } catch (RestClientException exception) {
            log.warn("두루누비 API 응답을 처리하지 못했습니다.");
            throw new CourseMetadataSyncException(Reason.INVALID_RESPONSE, exception);
        }
        return parsePage(responseBody);
    }

    private CourseListPage parsePage(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            tripKtoResultCode();
            throw new CourseMetadataSyncException(Reason.INVALID_RESPONSE);
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root == null || !root.isObject()) {
                tripKtoResultCode();
                throw new CourseMetadataSyncException(Reason.INVALID_RESPONSE);
            }
            JsonNode response = root.path("response");
            String resultCode = textOrNull(response.path("header").path("resultCode"));
            if (!SUCCESS_CODE.equals(resultCode)) {
                tripKtoResultCode();
                throw new CourseMetadataSyncException(Reason.INVALID_RESPONSE);
            }
            JsonNode body = response.path("body");
            int totalCount = body.path("totalCount").asInt(-1);
            if (totalCount < 0) {
                throw new CourseMetadataSyncException(Reason.INVALID_RESPONSE);
            }
            return new CourseListPage(
                    itemNodes(body.path("items").path("item")),
                    totalCount);
        } catch (JsonProcessingException exception) {
            tripKtoResultCode();
            log.warn("두루누비 API가 JSON이 아닌 응답을 반환했습니다.");
            throw new CourseMetadataSyncException(Reason.INVALID_RESPONSE, exception);
        }
    }

    private void tripKtoResultCode() {
        upstreamLoadGuard.tripKtoResultCode(UpstreamEndpoint.KTO_DURUNUBI_COURSE);
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

    private ParsedMetadata parseMetadata(JsonNode item) {
        String courseId = normalizedText(item.path("crsIdx"), false);
        if (courseId == null) {
            throw new CourseMetadataSyncException(Reason.INVALID_RESPONSE);
        }
        int invalid = 0;
        String courseName = normalizedText(item.path("crsKorNm"), false);
        if (courseName == null) {
            invalid++;
        }
        String level = normalizedText(item.path("crsLevel"), false);
        CourseDifficulty difficulty = CourseDifficulty.fromKtoLevel(level);
        if (difficulty == null) {
            invalid++;
        }
        String cycle = normalizedText(item.path("crsCycle"), false);
        if (cycle == null) {
            invalid++;
        }
        String summary = normalizedText(item.path("crsSummary"), true);
        if (summary == null) {
            invalid++;
        }
        return new ParsedMetadata(
                new CourseMetadata(courseId, courseName, difficulty, cycle, summary),
                invalid);
    }

    private String normalizedText(JsonNode node, boolean stripHtml) {
        String value = textOrNull(node);
        if (value == null) {
            return null;
        }
        if (stripHtml) {
            value = HtmlUtils.htmlUnescape(HTML_TAG.matcher(value).replaceAll(" "));
        }
        value = WHITESPACE.matcher(value).replaceAll(" ").strip();
        return value.isEmpty()
                ? null
                : Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private String textOrNull(JsonNode node) {
        if (!node.isValueNode()) {
            return null;
        }
        String value = node.asText().strip();
        return value.isEmpty() ? null : value;
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

    private record CourseListPage(List<JsonNode> items, int totalCount) {}

    private record ParsedMetadata(CourseMetadata metadata, int invalidFieldCount) {}
}
