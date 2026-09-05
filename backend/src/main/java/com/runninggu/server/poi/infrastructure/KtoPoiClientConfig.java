package com.runninggu.server.poi.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.common.upstream.UpstreamGuardedClientHttpRequestFactory;
import com.runninggu.server.common.upstream.UpstreamLoadGuard;
import com.runninggu.server.common.upstream.UpstreamLoadGuardInterceptor;
import com.runninggu.server.common.upstream.UpstreamProvider;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KtoPoiProperties.class)
public class KtoPoiClientConfig {

    @Bean
    public KtoPoiClient ktoPoiClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            KtoPoiProperties properties,
            UpstreamLoadGuard upstreamLoadGuard) {
        UpstreamLoadGuardInterceptor interceptor = new UpstreamLoadGuardInterceptor(
                upstreamLoadGuard,
                UpstreamProvider.KTO);
        RestClient korRestClient = restClient(
                builder.clone(),
                properties.baseUrl(),
                properties.connectTimeout(),
                properties.readTimeout(),
                upstreamLoadGuard,
                interceptor);
        RestClient wellnessRestClient = restClient(
                builder.clone(),
                properties.wellnessBaseUrl(),
                properties.connectTimeout(),
                properties.readTimeout(),
                upstreamLoadGuard,
                interceptor);
        return new KtoPoiClient(
                korRestClient,
                wellnessRestClient,
                objectMapper,
                properties.serviceKey(),
                upstreamLoadGuard);
    }

    private RestClient restClient(
            RestClient.Builder builder,
            URI baseUrl,
            Duration connectTimeout,
            Duration readTimeout,
            UpstreamLoadGuard upstreamLoadGuard,
            UpstreamLoadGuardInterceptor interceptor) {
        UpstreamGuardedClientHttpRequestFactory requestFactory =
                new UpstreamGuardedClientHttpRequestFactory(upstreamLoadGuard);
        requestFactory.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));
        return builder
                .baseUrl(baseUrl.toString())
                .requestFactory(requestFactory)
                .requestInterceptor(interceptor)
                .build();
    }
}
