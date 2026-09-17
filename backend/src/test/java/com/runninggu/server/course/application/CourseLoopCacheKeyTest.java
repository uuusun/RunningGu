package com.runninggu.server.course.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CourseLoopCacheKeyTest {

    @Test
    void 진입점은_소수_4자리로_반올림하고_목표거리의_scale은_무시한다() {
        CourseLoopCacheKey first = CourseLoopCacheKey.from(
                new BigDecimal("37.52641"),
                new BigDecimal("126.92271"),
                new BigDecimal("5"));
        CourseLoopCacheKey same = CourseLoopCacheKey.from(
                new BigDecimal("37.52644"),
                new BigDecimal("126.92274"),
                new BigDecimal("5.0"));
        CourseLoopCacheKey different = CourseLoopCacheKey.from(
                new BigDecimal("37.52645"),
                new BigDecimal("126.92275"),
                new BigDecimal("5"));

        assertThat(first).isEqualTo(same);
        assertThat(first).isNotEqualTo(different);
    }
}
