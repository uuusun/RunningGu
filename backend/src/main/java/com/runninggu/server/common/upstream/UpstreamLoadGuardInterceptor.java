package com.runninggu.server.common.upstream;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** RestClient의 재시도·페이지 처리보다 안쪽에서 실제 HTTP 시도마다 guard를 적용한다. */
public final class UpstreamLoadGuardInterceptor implements ClientHttpRequestInterceptor {

    private final UpstreamLoadGuard guard;
    private final UpstreamProvider expectedProvider;

    public UpstreamLoadGuardInterceptor(
            UpstreamLoadGuard guard,
            UpstreamProvider expectedProvider) {
        this.guard = guard;
        this.expectedProvider = java.util.Objects.requireNonNull(
                expectedProvider,
                "expectedProvider");
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution)
            throws IOException {
        UpstreamAttempt attempt = guard.reserve(request.getURI(), expectedProvider);
        if (!attempt.monitored()) {
            return execution.execute(request, body);
        }
        try {
            ClientHttpResponse response = execution.execute(request, body);
            int statusCode = response.getStatusCode().value();
            if (guard.isImmediateHttpTrip(statusCode)) {
                try {
                    guard.recordHttpStatus(attempt, statusCode);
                } catch (UpstreamLoadGuardException exception) {
                    response.close();
                    throw exception;
                }
            }
            return new UpstreamGuardedClientHttpResponse(
                    response,
                    guard,
                    attempt,
                    statusCode);
        } catch (IOException exception) {
            if (causedByTimeout(exception)) {
                guard.recordTimeout(attempt);
            } else {
                guard.recordIoFailure(attempt);
            }
            throw exception;
        }
    }

    static boolean causedByTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
