package com.runninggu.server.poi.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KtoPoiProperties.class)
public class KtoPoiClientConfig {

    @Bean
    public KtoPoiClient ktoPoiClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            KtoPoiProperties properties) {
        RestClient korRestClient = restClient(
                builder.clone(),
                properties.baseUrl(),
                properties.connectTimeout(),
                properties.readTimeout());
        RestClient wellnessRestClient = restClient(
                builder.clone(),
                properties.wellnessBaseUrl(),
                properties.connectTimeout(),
                properties.readTimeout());
        return new KtoPoiClient(
                korRestClient,
                wellnessRestClient,
                objectMapper,
                properties.serviceKey());
    }

    private RestClient restClient(
            RestClient.Builder builder,
            URI baseUrl,
            Duration connectTimeout,
            Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));
        return builder
                .baseUrl(baseUrl.toString())
                .requestFactory(requestFactory)
                .build();
    }
}
