package com.runninggu.server.common.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class UpstreamEndpointTest {

    @Test
    void 승인된_endpoint_목록은_정확히_여덟_개다() {
        assertThat(UpstreamEndpoint.values())
                .containsExactly(
                        UpstreamEndpoint.KAKAO_CATEGORY,
                        UpstreamEndpoint.KAKAO_KEYWORD,
                        UpstreamEndpoint.KAKAO_ACCESS_TOKEN_INFO,
                        UpstreamEndpoint.KAKAO_USER_ME,
                        UpstreamEndpoint.KTO_SEARCH_FESTIVAL,
                        UpstreamEndpoint.KTO_KOR_LOCATION,
                        UpstreamEndpoint.KTO_WELLNESS_LOCATION,
                        UpstreamEndpoint.KTO_DURUNUBI_COURSE);
    }

    @Test
    void 운영_host와_path의_정확한_조합만_allowlist로_해석한다() {
        assertResolved(
                "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=FD6",
                UpstreamEndpoint.KAKAO_CATEGORY);
        assertResolved(
                "https://dapi.kakao.com/v2/local/search/keyword.json?query=fixture",
                UpstreamEndpoint.KAKAO_KEYWORD);
        assertResolved(
                "https://kapi.kakao.com/v1/user/access_token_info",
                UpstreamEndpoint.KAKAO_ACCESS_TOKEN_INFO);
        assertResolved(
                "https://kapi.kakao.com/v2/user/me",
                UpstreamEndpoint.KAKAO_USER_ME);
        assertResolved(
                "https://apis.data.go.kr/B551011/KorService2/searchFestival2",
                UpstreamEndpoint.KTO_SEARCH_FESTIVAL);
        assertResolved(
                "https://apis.data.go.kr/B551011/KorService2/locationBasedList2",
                UpstreamEndpoint.KTO_KOR_LOCATION);
        assertResolved(
                "https://apis.data.go.kr/B551011/WellnessTursmService/locationBasedList",
                UpstreamEndpoint.KTO_WELLNESS_LOCATION);
        assertResolved(
                "https://apis.data.go.kr/B551011/Durunubi/courseList",
                UpstreamEndpoint.KTO_DURUNUBI_COURSE);
    }

    @Test
    void scheme_port_userinfo_fragment와_path가_다르면_허용하지_않는다() {
        assertThat(UpstreamEndpoint.resolve(
                        URI.create("http://dapi.kakao.com/v2/local/search/category.json")))
                .isEmpty();
        assertThat(UpstreamEndpoint.resolve(
                        URI.create("https://dapi.kakao.com:8443/v2/local/search/category.json")))
                .isEmpty();
        assertThat(UpstreamEndpoint.resolve(
                        URI.create("https://user@dapi.kakao.com/v2/local/search/category.json")))
                .isEmpty();
        assertThat(UpstreamEndpoint.resolve(
                        URI.create("https://dapi.kakao.com/v2/local/search/category.json#fragment")))
                .isEmpty();
        assertThat(UpstreamEndpoint.resolve(
                        URI.create("https://dapi.kakao.com/v2/local/search/category.json/")))
                .isEmpty();
    }

    private void assertResolved(String uri, UpstreamEndpoint endpoint) {
        assertThat(UpstreamEndpoint.resolve(URI.create(uri))).contains(endpoint);
    }
}
