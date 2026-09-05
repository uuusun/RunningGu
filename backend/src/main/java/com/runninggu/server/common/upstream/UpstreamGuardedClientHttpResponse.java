package com.runninggu.server.common.upstream;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

/** 본문 소비가 끝날 때까지 HTTP 성공 기록을 미루고 body timeout도 같은 attempt로 trip한다. */
final class UpstreamGuardedClientHttpResponse implements ClientHttpResponse {

    private final ClientHttpResponse delegate;
    private final UpstreamLoadGuard guard;
    private final UpstreamAttempt attempt;
    private final int statusCode;

    private GuardedBody body;
    private Outcome outcome = Outcome.OPEN;

    UpstreamGuardedClientHttpResponse(
            ClientHttpResponse delegate,
            UpstreamLoadGuard guard,
            UpstreamAttempt attempt,
            int statusCode) {
        this.delegate = delegate;
        this.guard = guard;
        this.attempt = attempt;
        this.statusCode = statusCode;
    }

    @Override
    public HttpStatusCode getStatusCode() {
        return HttpStatusCode.valueOf(statusCode);
    }

    @Override
    public String getStatusText() throws IOException {
        try {
            return delegate.getStatusText();
        } catch (IOException exception) {
            throw classify(exception);
        }
    }

    @Override
    public HttpHeaders getHeaders() {
        return delegate.getHeaders();
    }

    @Override
    public synchronized InputStream getBody() throws IOException {
        if (body != null) {
            return body;
        }
        try {
            body = new GuardedBody(delegate.getBody());
            return body;
        } catch (IOException exception) {
            throw classify(exception);
        }
    }

    @Override
    public void close() {
        try {
            delegate.close();
            completeOnce();
        } catch (UpstreamLoadGuardException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            classify(exception);
            throw exception;
        }
    }

    private synchronized void completeOnce() {
        if (outcome != Outcome.OPEN) {
            return;
        }
        outcome = Outcome.COMPLETE;
        guard.recordHttpStatus(attempt, statusCode);
    }

    private IOException classify(IOException exception) {
        if (UpstreamLoadGuardInterceptor.causedByTimeout(exception)) {
            recordTimeout();
            return exception;
        }
        recordIoFailure();
        return exception;
    }

    private void classify(RuntimeException exception) {
        if (UpstreamLoadGuardInterceptor.causedByTimeout(exception)) {
            recordTimeout();
            return;
        }
        recordIoFailure();
    }

    private void recordTimeout() {
        synchronized (this) {
            if (outcome == Outcome.TIMEOUT) {
                return;
            }
            outcome = Outcome.TIMEOUT;
        }
        guard.recordTimeout(attempt);
    }

    private void recordIoFailure() {
        synchronized (this) {
            if (outcome != Outcome.OPEN) {
                return;
            }
            outcome = Outcome.IO_FAILURE;
        }
        guard.recordIoFailure(attempt);
    }

    private final class GuardedBody extends FilterInputStream {

        private GuardedBody(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            try {
                int value = super.read();
                if (value == -1) {
                    completeOnce();
                }
                return value;
            } catch (IOException exception) {
                throw classify(exception);
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                int count = super.read(bytes, offset, length);
                if (count == -1) {
                    completeOnce();
                }
                return count;
            } catch (IOException exception) {
                throw classify(exception);
            }
        }

        @Override
        public long skip(long count) throws IOException {
            try {
                return super.skip(count);
            } catch (IOException exception) {
                throw classify(exception);
            }
        }

        @Override
        public int available() throws IOException {
            try {
                return super.available();
            } catch (IOException exception) {
                throw classify(exception);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
                completeOnce();
            } catch (IOException exception) {
                throw classify(exception);
            }
        }
    }

    private enum Outcome {
        OPEN,
        COMPLETE,
        IO_FAILURE,
        TIMEOUT
    }
}
