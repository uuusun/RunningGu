package com.runninggu.server.course.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class CourseBundleReaderTest {

    @Test
    void 계약을_만족하는_classpath_번들을_불변_snapshot으로_읽는다() {
        CourseBundleReader reader = reader(1);

        var snapshot = reader.read();

        assertThat(snapshot.sources()).extracting("key")
                .containsExactly("durunubi", "trail-lab");
        assertThat(snapshot.courses()).hasSize(4);
        assertThat(snapshot.courses().getFirst().courseId()).isEqualTo("C001");
        assertThat(snapshot.courses().getFirst().syncedAt()).isNull();
        assertThat(snapshot.courses().getFirst().points()).hasSize(2);
    }

    @Test
    void 승인된_최소_코스수보다_작은_번들은_시작_전에_거부한다() {
        CourseBundleReader reader = reader(5);

        assertThatThrownBy(reader::read)
                .isInstanceOf(CourseBundleValidationException.class)
                .hasMessageContaining("하한");
    }

    @Test
    void classpath_번들이_없으면_서버_시작에_사용할_snapshot을_만들지_않는다() {
        CourseBundleReader reader = new CourseBundleReader(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                new CourseCatalogProperties("classpath:data/missing-courses.json", 1));

        assertThatThrownBy(reader::read)
                .isInstanceOf(CourseBundleValidationException.class)
                .hasMessageContaining("찾을 수 없습니다");
    }

    private CourseBundleReader reader(int minimumCourseCount) {
        return new CourseBundleReader(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                new CourseCatalogProperties(
                        "classpath:data/courses.json",
                        minimumCourseCount));
    }
}
