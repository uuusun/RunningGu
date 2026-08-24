package com.runninggu.server;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

abstract class PostgreSqlContainerSupport {

    private static final String TEST_JWT_SECRET =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10"));

    static {
        POSTGRESQL.start();
    }

    @DynamicPropertySource
    static void registerPostgreSqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        // 여러 SpringBootTest 컨텍스트가 캐시돼도 PostgreSQL 기본 연결 상한을 넘지 않게 한다.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
        registry.add("runninggu.auth.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("runninggu.external.kakao-user-info.app-id", () -> 1234L);
        registry.add("runninggu.course.catalog.minimum-course-count", () -> "1");
        registry.add("runninggu.course.sync.enabled", () -> "false");
    }
}
