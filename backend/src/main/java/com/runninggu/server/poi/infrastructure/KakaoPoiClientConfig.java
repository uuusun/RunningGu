package com.runninggu.server.poi.infrastructure;

import com.runninggu.server.common.upstream.UpstreamGuardedClientHttpRequestFactory;
import com.runninggu.server.common.upstream.UpstreamLoadGuard;
import com.runninggu.server.common.upstream.UpstreamLoadGuardInterceptor;
import com.runninggu.server.common.upstream.UpstreamProvider;
import com.runninggu.server.geocode.infrastructure.KakaoLocalProperties;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class KakaoPoiClientConfig {

    @Bean
    public KakaoPoiClient kakaoPoiClient(
            RestClient.Builder builder,
            KakaoLocalProperties properties,
            UpstreamLoadGuard upstreamLoadGuard) {
        UpstreamGuardedClientHttpRequestFactory requestFactory = requestFactory(
                properties.connectTimeout(),
                properties.readTimeout(),
                upstreamLoadGuard);
        RestClient restClient = builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .requestInterceptor(new UpstreamLoadGuardInterceptor(
                        upstreamLoadGuard,
                        UpstreamProvider.KAKAO))
                .build();
        return new KakaoPoiClient(restClient, properties.restKey());
    }

    private UpstreamGuardedClientHttpRequestFactory requestFactory(
            Duration connectTimeout,
            Duration readTimeout,
            UpstreamLoadGuard upstreamLoadGuard) {
        UpstreamGuardedClientHttpRequestFactory requestFactory =
                new UpstreamGuardedClientHttpRequestFactory(upstreamLoadGuard);
        requestFactory.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));
        return requestFactory;
    }
}
