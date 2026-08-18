package com.runninggu.server.common.web;

import com.runninggu.server.common.error.ProblemDetailFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(ProblemDetailFactory.TRACE_ID_ATTRIBUTE, traceId);

        try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", traceId)) {
            filterChain.doFilter(request, response);
        }
    }
}
