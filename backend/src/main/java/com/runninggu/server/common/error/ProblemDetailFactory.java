package com.runninggu.server.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/** RFC 9457 응답의 공통 필드와 런닝구 확장 필드를 한곳에서 생성한다. (API 명세 §0-3) */
@Component
public class ProblemDetailFactory {

    public static final String TRACE_ID_ATTRIBUTE =
            ProblemDetailFactory.class.getName() + ".traceId";

    public ProblemDetail create(
            ErrorCode errorCode,
            String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.status(), detail);
        problem.setType(errorCode.type());
        problem.setTitle(errorCode.title());
        problem.setInstance(safeInstance(request));
        problem.setProperty("code", errorCode.name());
        problem.setProperty("traceId", traceId(request));
        return problem;
    }

    public ProblemDetail validation(
            String detail,
            HttpServletRequest request,
            List<FieldViolation> errors) {
        ProblemDetail problem = create(ErrorCode.VALIDATION_FAILED, detail, request);
        problem.setProperty("errors", errors);
        return problem;
    }

    public String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (traceId instanceof String value && !value.isBlank()) {
            return value;
        }

        String generated = UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(TRACE_ID_ATTRIBUTE, generated);
        return generated;
    }

    private URI safeInstance(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        return requestUri == null || requestUri.isBlank() ? null : URI.create(requestUri);
    }

    public record FieldViolation(String field, String reason) {}
}
