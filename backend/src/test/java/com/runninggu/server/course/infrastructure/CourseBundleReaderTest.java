package com.runninggu.server.course.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
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

    /**
     * 운영 번들의 261코스 하한을 직접 읽어 검증하는 유일한 회귀 테스트다.
     * 테스트용 4건 픽스처와 별개로 운영 번들 축소를 막으므로 삭제하거나 비활성화하지 않는다.
     */
    @Test
    void 저장소의_운영_번들_261코스를_같은_소비자_검증으로_읽는다() {
        String productionBundle = Path.of("..", "data", "courses.json")
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString();
        CourseBundleReader reader = new CourseBundleReader(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                new CourseCatalogProperties(productionBundle, 261));

        var snapshot = reader.read();

        assertThat(snapshot.courses()).hasSize(261);
        assertThat(snapshot.courses())
                .allSatisfy(course -> assertThat(course.syncedAt()).isNull());
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
