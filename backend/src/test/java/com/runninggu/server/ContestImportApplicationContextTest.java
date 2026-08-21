package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.runninggu.server.contest.application.ContestSnapshotImporter;
import com.runninggu.server.auth.application.EmailAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.JwtEncoder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContestImportApplicationContextTest extends PostgreSqlContainerSupport {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void 비웹_컨텍스트는_보안_필터_없이_Importer를_구동한다() {
        assertThat(applicationContext.getBeansOfType(SecurityFilterChain.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(JwtEncoder.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(EmailAuthService.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ContestSnapshotImporter.class)).hasSize(1);
    }
}
