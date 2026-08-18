package com.runninggu.server.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ApiProblemWriter {

    private final ObjectMapper objectMapper;
    private final ProblemDetailFactory problemDetailFactory;

    public ApiProblemWriter(
            ObjectMapper objectMapper,
            ProblemDetailFactory problemDetailFactory) {
        this.objectMapper = objectMapper;
        this.problemDetailFactory = problemDetailFactory;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode) throws IOException {
        ProblemDetail problem = problemDetailFactory.create(
                errorCode,
                errorCode.title(),
                request);

        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
