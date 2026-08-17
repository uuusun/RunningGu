package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.runninggu.server.common.config.ClockConfig;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ApplicationContextTest extends PostgreSqlContainerSupport {

    @Autowired
    private Clock businessClock;

    @Test
    void 애플리케이션과_KST_시계가_구동된다() {
        assertThat(businessClock.getZone()).isEqualTo(ClockConfig.KST);
    }
}
