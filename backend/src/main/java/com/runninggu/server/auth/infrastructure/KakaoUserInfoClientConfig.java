package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.application.KakaoUserInfoProvider;
import com.runninggu.server.common.upstream.UpstreamGuardedClientHttpRequestFactory;
import com.runninggu.server.common.upstream.UpstreamLoadGuard;
import com.runninggu.server.common.upstream.UpstreamLoadGuardInterceptor;
import com.runninggu.server.common.upstream.UpstreamProvider;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(KakaoUserInfoProperties.class)
public class KakaoUserInfoClientConfig {

    @Bean
    public KakaoUserInfoProvider kakaoUserInfoProvider(
            RestClient.Builder builder,
            KakaoUserInfoProperties properties,
            UpstreamLoadGuard upstreamLoadGuard) {
        UpstreamGuardedClientHttpRequestFactory requestFactory =
                new UpstreamGuardedClientHttpRequestFactory(upstreamLoadGuard);
        requestFactory.setConnectTimeout(toMillis(properties.connectTimeout()));
        requestFactory.setReadTimeout(toMillis(properties.readTimeout()));

        RestClient restClient = builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .requestInterceptor(new UpstreamLoadGuardInterceptor(
                        upstreamLoadGuard,
                        UpstreamProvider.KAKAO))
                .build();
        return new KakaoUserInfoClient(restClient, properties.appId());
    }

    private int toMillis(Duration duration) {
        return Math.toIntExact(duration.toMillis());
    }
}
