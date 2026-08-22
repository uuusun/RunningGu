package com.runninggu.server.common.error;

import com.runninggu.server.common.error.ProblemDetailFactory.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String INVALID_REQUEST_DETAIL = "요청 값을 확인해 주세요.";
    private static final String INTERNAL_ERROR_DETAIL = "요청을 처리하지 못했습니다.";

    private final ProblemDetailFactory problemDetailFactory;

    public GlobalExceptionHandler(ProblemDetailFactory problemDetailFactory) {
        this.problemDetailFactory = problemDetailFactory;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(
            ApiException exception,
            HttpServletRequest request) {
        ProblemDetail problem = problemDetailFactory.create(
                exception.errorCode(),
                exception.getMessage(),
                request);
        return problemResponse(problem, exception.errorCode());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        String detail = exception.getName() + " 값이 올바르지 않습니다.";
        ProblemDetail problem = problemDetailFactory.create(
                ErrorCode.VALIDATION_FAILED,
                detail,
                request);
        return problemResponse(problem, ErrorCode.VALIDATION_FAILED);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        String constraintName = constraintName(exception);
        ErrorCode errorCode = switch (constraintName == null ? "" : constraintName) {
            case "uk_login_identity_provider_subject" -> ErrorCode.EMAIL_DUPLICATED;
            case "uk_app_user_nickname_key" -> ErrorCode.NICKNAME_DUPLICATED;
            default -> null;
        };
        if (errorCode == null) {
            return handleUnexpectedException(exception, request);
        }
        ProblemDetail problem = problemDetailFactory.create(
                errorCode,
                errorCode.title(),
                request);
        return problemResponse(problem, errorCode);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request) {
        String traceId = problemDetailFactory.traceId(request);
        log.error("처리되지 않은 서버 오류가 발생했습니다. traceId={}", traceId, exception);

        ProblemDetail problem = problemDetailFactory.create(
                ErrorCode.INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR_DETAIL,
                request);
        return problemResponse(problem, ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest webRequest) {
        HttpServletRequest request = ((ServletWebRequest) webRequest).getRequest();
        List<FieldViolation> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldViolation)
                .toList();
        ProblemDetail problem = problemDetailFactory.validation(
                INVALID_REQUEST_DETAIL,
                request,
                errors);

        return ResponseEntity
                .status(ErrorCode.VALIDATION_FAILED.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest webRequest) {
        HttpServletRequest request = ((ServletWebRequest) webRequest).getRequest();
        ProblemDetail problem = problemDetailFactory.create(
                ErrorCode.VALIDATION_FAILED,
                exception.getParameterName() + " 값이 필요합니다.",
                request);
        return ResponseEntity
                .status(ErrorCode.VALIDATION_FAILED.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private FieldViolation toFieldViolation(FieldError error) {
        String reason = error.getDefaultMessage() == null
                ? "올바르지 않은 값입니다."
                : error.getDefaultMessage();
        return new FieldViolation(error.getField(), reason);
    }

    private String constraintName(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
            current = current.getCause();
        }
        return null;
    }

    private ResponseEntity<ProblemDetail> problemResponse(
            ProblemDetail problem,
            ErrorCode errorCode) {
        return ResponseEntity
                .status(errorCode.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
