package com.runninggu.server.course.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.runninggu.server.common.config.CacheConfig;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(classes = {
    CacheConfig.class,
    CachedCourseLoopGeometryQuery.class,
    CachedCourseLoopGeometryQueryTest.TestConfig.class
})
class CachedCourseLoopGeometryQueryTest {

    @Autowired
    private CachedCourseLoopGeometryQuery query;

    @Autowired
    private OsmRouteGenerator generator;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void resetGeneratorAndCache() {
        reset(generator);
        cacheManager.getCache(CacheConfig.COURSE_LOOP_GEOMETRY_CACHE).clear();
    }

    @Test
    void 반올림한_진입점과_목표거리가_같으면_geometry를_한번만_생성한다() {
        given(generator.generate(any(), any(), any()))
                .willReturn(OsmRouteSearchResult.normal(Optional.of(route())));

        CourseLoopGeometryResult first = query.find(
                new BigDecimal("37.52641"),
                new BigDecimal("126.92271"),
                new BigDecimal("5"));
        CourseLoopGeometryResult second = query.find(
                new BigDecimal("37.52644"),
                new BigDecimal("126.92274"),
                new BigDecimal("5.0"));

        assertThat(first.geometry()).isPresent();
        assertThat(second).isSameAs(first);
        verify(generator, times(1)).generate(any(), any(), any());
    }

    @Test
    void 정상_0건은_래퍼로_캐시한다() {
        given(generator.generate(any(), any(), any()))
                .willReturn(OsmRouteSearchResult.normal(Optional.empty()));

        CourseLoopGeometryResult first = query.find(
                new BigDecimal("37.5264"),
                new BigDecimal("126.9227"),
                new BigDecimal("5"));
        CourseLoopGeometryResult second = query.find(
                new BigDecimal("37.5264"),
                new BigDecimal("126.9227"),
                new BigDecimal("5"));

        assertThat(first.geometry()).isEmpty();
        assertThat(second).isSameAs(first);
        verify(generator, times(1)).generate(any(), any(), any());
    }

    @Test
    void 원천_실패는_부분_후보가_있어도_503이고_캐시하지_않는다() {
        given(generator.generate(any(), any(), any()))
                .willReturn(OsmRouteSearchResult.degraded(Optional.of(route())));

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> query.find(
                            new BigDecimal("37.5264"),
                            new BigDecimal("126.9227"),
                            new BigDecimal("5")))
                    .isInstanceOfSatisfying(ApiException.class, exception ->
                            assertThat(exception.errorCode())
                                    .isEqualTo(ErrorCode.COURSE_SOURCES_UNAVAILABLE));
        }
        verify(generator, times(2)).generate(any(), any(), any());
    }

    private OsmGeneratedRoute route() {
        return new OsmGeneratedRoute(
                "osm:discarded",
                CourseDataSource.OSM_GENERATED,
                "캐시 제외 이름",
                999,
                new BigDecimal("37.5264"),
                new BigDecimal("126.9227"),
                CourseDifficulty.EASY,
                new BigDecimal("5.06"),
                46,
                21,
                List.of(12, 13, 12),
                false,
                "???");
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        OsmRouteGenerator osmRouteGenerator() {
            return mock(OsmRouteGenerator.class);
        }
    }
}
