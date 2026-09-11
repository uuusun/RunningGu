package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.runninggu.server.auth.infrastructure.AgreementProperties;
import com.runninggu.server.common.config.ClockConfig;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ApplicationContextTest extends PostgreSqlContainerSupport {

    @Autowired
    private Clock businessClock;

    @Autowired
    private AgreementProperties agreementProperties;

    @Test
    void 애플리케이션과_KST_시계가_구동된다() {
        assertThat(businessClock.getZone()).isEqualTo(ClockConfig.KST);
    }

    /**
     * 활성 약관 버전을 못으로 박는다. (이슈 #265 · D-32)
     *
     * <p>가입 요청은 boolean 만 오고 <b>버전은 서버가 붙인다</b>(API 명세 §1-5). 그래서 앱이
     * 보여준 문안과 서버가 남긴 버전이 어긋나면 동의 이력이 무엇에 대한 동의인지 알 수 없다
     * (NFR-12). 앱 쪽 짝은 {@code AgreementDoc.version} 과 {@code AgreementTextsTest} 이고,
     * 올릴 때는 <b>같은 PR 에서</b> 둘을 함께 바꾼다.
     *
     * <p>셋이 서로 다르다 — PRIVACY 만 1.1 을 건너뛰고 1.2 로 갔다(기기 위치 기능이 빠져서다 ·
     * #215). 하나로 묶어 두지 않는 이유가 이것이다.
     */
    @Test
    void 활성_약관_버전이_앱_문안과_같은_값이다() {
        assertThat(agreementProperties.tosVersion()).isEqualTo("1.1");
        assertThat(agreementProperties.privacyVersion()).isEqualTo("1.2");
        assertThat(agreementProperties.marketingVersion()).isEqualTo("1.1");
    }
}
