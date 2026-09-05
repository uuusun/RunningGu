package com.runninggu.server.common.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import org.junit.jupiter.api.Test;

class UpstreamGuardedClientHttpRequestFactoryTest {

    @Test
    void guard_비활성화이면_기존_GET_자동_redirect_동작을_유지한다() throws Exception {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(disabledProperties());
        TestableFactory factory = new TestableFactory(guard);
        FakeHttpURLConnection connection = connection();

        factory.prepare(connection, "GET");

        assertThat(guard.enabled()).isFalse();
        assertThat(connection.getInstanceFollowRedirects()).isTrue();
    }

    @Test
    void guard_활성화이면_GET_자동_redirect를_비활성화한다() throws Exception {
        UpstreamLoadGuard guard = new UpstreamLoadGuard(enabledProperties());
        TestableFactory factory = new TestableFactory(guard);
        FakeHttpURLConnection connection = connection();

        factory.prepare(connection, "GET");

        assertThat(guard.enabled()).isTrue();
        assertThat(connection.getInstanceFollowRedirects()).isFalse();
    }

    @Test
    void guard_비활성화이면_POST의_기존_non_redirect_동작도_바꾸지_않는다()
            throws Exception {
        TestableFactory factory =
                new TestableFactory(new UpstreamLoadGuard(disabledProperties()));
        FakeHttpURLConnection connection = connection();

        factory.prepare(connection, "POST");

        assertThat(connection.getInstanceFollowRedirects()).isFalse();
    }

    private FakeHttpURLConnection connection() throws Exception {
        return new FakeHttpURLConnection(
                URI.create("https://example.test/resource").toURL());
    }

    private UpstreamLoadGuardProperties disabledProperties() {
        return new UpstreamLoadGuardProperties(false, null, null, null, null);
    }

    private UpstreamLoadGuardProperties enabledProperties() {
        return new UpstreamLoadGuardProperties(
                true,
                "staging",
                "load-20260905",
                10,
                new UpstreamLoadGuardProperties.EndpointLimits(
                        10,
                        10,
                        10,
                        10,
                        10,
                        10,
                        10,
                        10));
    }

    private static final class TestableFactory
            extends UpstreamGuardedClientHttpRequestFactory {

        private TestableFactory(UpstreamLoadGuard guard) {
            super(guard);
        }

        private void prepare(HttpURLConnection connection, String method)
                throws IOException {
            prepareConnection(connection, method);
        }
    }

    private static final class FakeHttpURLConnection extends HttpURLConnection {

        private FakeHttpURLConnection(URL url) {
            super(url);
        }

        @Override
        public void disconnect() {}

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {}
    }
}
