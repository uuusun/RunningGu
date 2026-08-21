package com.runninggu.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "server.forward-headers-strategy=framework")
@AutoConfigureMockMvc
class ForwardedHeaderRateLimitIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 신뢰_프록시_설정에서는_Forwarded_클라이언트별로_IP_제한을_분리한다() throws Exception {
        for (int count = 1; count <= 31; count++) {
            mockMvc.perform(get("/api/auth/email/exists")
                            .param("email", "forwarded-" + count + "@example.com")
                            .header("Forwarded", "for=192.0.2." + count)
                            .with(request -> {
                                request.setRemoteAddr("10.0.0.10");
                                return request;
                            }))
                    .andExpect(status().isOk());
        }
    }
}
