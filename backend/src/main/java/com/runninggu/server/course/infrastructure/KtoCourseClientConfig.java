package com.runninggu.server.course.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.common.upstream.UpstreamGuardedClientHttpRequestFactory;
import com.runninggu.server.common.upstream.UpstreamLoadGuard;
import com.runninggu.server.common.upstream.UpstreamLoadGuardInterceptor;
import com.runninggu.server.common.upstream.UpstreamProvider;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KtoCourseProperties.class)
public class KtoCourseClientConfig {

    @Bean
    public KtoCourseClient ktoCourseClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            KtoCourseProperties properties,
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
                        UpstreamProvider.KTO))
                .build();
        return new KtoCourseClient(
                restClient,
                objectMapper,
                properties.serviceKey(),
                upstreamLoadGuard);
    }

    private int toMillis(Duration duration) {
        return Math.toIntExact(duration.toMillis());
    }
}
