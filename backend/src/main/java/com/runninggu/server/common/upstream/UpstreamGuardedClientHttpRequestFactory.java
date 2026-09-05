package com.runninggu.server.common.upstream;

import java.io.IOException;
import java.net.HttpURLConnection;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/** guard 활성 시 HttpURLConnection의 GET 자동 redirect를 꺼 우회 호출을 막는다. */
public class UpstreamGuardedClientHttpRequestFactory
        extends SimpleClientHttpRequestFactory {

    private final UpstreamLoadGuard guard;

    public UpstreamGuardedClientHttpRequestFactory(UpstreamLoadGuard guard) {
        this.guard = java.util.Objects.requireNonNull(guard, "guard");
    }

    @Override
    protected void prepareConnection(
            HttpURLConnection connection,
            String httpMethod)
            throws IOException {
        super.prepareConnection(connection, httpMethod);
        if (guard.enabled()) {
            connection.setInstanceFollowRedirects(false);
        }
    }
}
