package com.runninggu.server.common.upstream;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runninggu.upstream-load-guard")
public record UpstreamLoadGuardProperties(
        boolean enabled,
        String deploymentEnvironment,
        String runId,
        Integer kakaoTotalLimit,
        EndpointLimits endpoints) {

    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_KAKAO_TOTAL_LIMIT = 5_000;
    private static final int MAX_KAKAO_ENDPOINT_LIMIT = 2_000;
    private static final int MAX_KTO_ENDPOINT_LIMIT = 100;

    public UpstreamLoadGuardProperties {
        if (enabled) {
            if (!"staging".equals(deploymentEnvironment)) {
                throw new IllegalArgumentException(
                        "upstream load guard can only be enabled in staging");
            }
            requireRunId(runId);
            requireWithinApprovedLimit(
                    "kakao-total-limit",
                    kakaoTotalLimit,
                    MAX_KAKAO_TOTAL_LIMIT);
            if (endpoints == null) {
                throw new IllegalArgumentException(
                        "upstream load guard endpoint limits are required");
            }
            endpoints.validate();
        }
    }

    int limitFor(UpstreamEndpoint endpoint) {
        if (!enabled || endpoints == null) {
            throw new IllegalStateException("upstream load guard is disabled");
        }
        return switch (endpoint) {
            case KAKAO_CATEGORY -> endpoints.kakaoCategory();
            case KAKAO_KEYWORD -> endpoints.kakaoKeyword();
            case KAKAO_ACCESS_TOKEN_INFO -> endpoints.kakaoAccessTokenInfo();
            case KAKAO_USER_ME -> endpoints.kakaoUserMe();
            case KTO_SEARCH_FESTIVAL -> endpoints.ktoSearchFestival();
            case KTO_KOR_LOCATION -> endpoints.ktoKorLocation();
            case KTO_WELLNESS_LOCATION -> endpoints.ktoWellnessLocation();
            case KTO_DURUNUBI_COURSE -> endpoints.ktoDurunubiCourse();
        };
    }

    private static void requireRunId(String value) {
        if (value == null || !RUN_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "upstream load guard run-id must match [A-Za-z0-9._-]{1,64}");
        }
    }

    private static void requireWithinApprovedLimit(
            String name,
            Integer value,
            int approvedMaximum) {
        if (value == null || value <= 0 || value > approvedMaximum) {
            throw new IllegalArgumentException(
                    "upstream load guard " + name
                            + " must be positive and no greater than "
                            + approvedMaximum);
        }
    }

    public record EndpointLimits(
            Integer kakaoCategory,
            Integer kakaoKeyword,
            Integer kakaoAccessTokenInfo,
            Integer kakaoUserMe,
            Integer ktoSearchFestival,
            Integer ktoKorLocation,
            Integer ktoWellnessLocation,
            Integer ktoDurunubiCourse) {

        private void validate() {
            requireWithinApprovedLimit(
                    "endpoints.kakao-category", kakaoCategory, MAX_KAKAO_ENDPOINT_LIMIT);
            requireWithinApprovedLimit(
                    "endpoints.kakao-keyword", kakaoKeyword, MAX_KAKAO_ENDPOINT_LIMIT);
            requireWithinApprovedLimit(
                    "endpoints.kakao-access-token-info", kakaoAccessTokenInfo, MAX_KAKAO_ENDPOINT_LIMIT);
            requireWithinApprovedLimit(
                    "endpoints.kakao-user-me", kakaoUserMe, MAX_KAKAO_ENDPOINT_LIMIT);
            requireWithinApprovedLimit(
                    "endpoints.kto-search-festival", ktoSearchFestival, MAX_KTO_ENDPOINT_LIMIT);
            requireWithinApprovedLimit(
                    "endpoints.kto-kor-location", ktoKorLocation, MAX_KTO_ENDPOINT_LIMIT);
            requireWithinApprovedLimit(
                    "endpoints.kto-wellness-location", ktoWellnessLocation, MAX_KTO_ENDPOINT_LIMIT);
            requireWithinApprovedLimit(
                    "endpoints.kto-durunubi-course", ktoDurunubiCourse, MAX_KTO_ENDPOINT_LIMIT);
        }
    }
}
