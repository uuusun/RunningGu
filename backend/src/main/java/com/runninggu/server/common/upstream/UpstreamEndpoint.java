package com.runninggu.server.common.upstream;

import java.net.URI;
import java.util.Arrays;
import java.util.Optional;

/** staging 부하 검증에서 호출을 허용하고 개별 예산을 부여하는 운영 endpoint다. */
public enum UpstreamEndpoint {
    KAKAO_CATEGORY(
            UpstreamProvider.KAKAO,
            "dapi.kakao.com",
            "/v2/local/search/category.json"),
    KAKAO_KEYWORD(
            UpstreamProvider.KAKAO,
            "dapi.kakao.com",
            "/v2/local/search/keyword.json"),
    KAKAO_ACCESS_TOKEN_INFO(
            UpstreamProvider.KAKAO,
            "kapi.kakao.com",
            "/v1/user/access_token_info"),
    KAKAO_USER_ME(
            UpstreamProvider.KAKAO,
            "kapi.kakao.com",
            "/v2/user/me"),
    KTO_SEARCH_FESTIVAL(
            UpstreamProvider.KTO,
            "apis.data.go.kr",
            "/B551011/KorService2/searchFestival2"),
    KTO_KOR_LOCATION(
            UpstreamProvider.KTO,
            "apis.data.go.kr",
            "/B551011/KorService2/locationBasedList2"),
    KTO_WELLNESS_LOCATION(
            UpstreamProvider.KTO,
            "apis.data.go.kr",
            "/B551011/WellnessTursmService/locationBasedList"),
    KTO_DURUNUBI_COURSE(
            UpstreamProvider.KTO,
            "apis.data.go.kr",
            "/B551011/Durunubi/courseList");

    private static final int HTTPS_DEFAULT_PORT = 443;

    private final UpstreamProvider provider;
    private final String host;
    private final String path;

    UpstreamEndpoint(UpstreamProvider provider, String host, String path) {
        this.provider = provider;
        this.host = host;
        this.path = path;
    }

    public UpstreamProvider provider() {
        return provider;
    }

    /** URI의 query는 비밀을 포함할 수 있으므로 읽거나 로그에 남기지 않고 host/path만 대조한다. */
    public static Optional<UpstreamEndpoint> resolve(URI uri) {
        if (uri == null || !isSecureProductionUri(uri)) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(endpoint -> endpoint.host.equalsIgnoreCase(uri.getHost()))
                .filter(endpoint -> endpoint.path.equals(uri.getRawPath()))
                .findFirst();
    }

    private static boolean isSecureProductionUri(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getFragment() == null
                && (uri.getPort() == -1 || uri.getPort() == HTTPS_DEFAULT_PORT);
    }
}
