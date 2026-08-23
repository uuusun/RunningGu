package com.runninggu.server.course.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.course.application.CourseCatalog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CourseCatalogProperties.class)
public class CourseCatalogConfig {

    @Bean
    public CourseBundleReader courseBundleReader(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            CourseCatalogProperties properties) {
        return new CourseBundleReader(objectMapper, resourceLoader, properties);
    }

    @Bean
    public CourseCatalog courseCatalog(CourseBundleReader reader) {
        return new CourseCatalog(reader.read());
    }
}
