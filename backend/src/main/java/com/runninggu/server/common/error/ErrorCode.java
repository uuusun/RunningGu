package com.runninggu.server.common.error;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** 앱이 HTTP 문구가 아니라 안정적인 code 값으로 오류를 분기하도록 한다. (SPEC §9.3) */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INVALID_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않습니다."),
    CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증 코드가 만료됐습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    CONTEST_NOT_FOUND(HttpStatus.NOT_FOUND, "대회를 찾을 수 없습니다."),
    NO_RESULT(HttpStatus.NOT_FOUND, "검색 결과를 찾을 수 없습니다."),
    EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    SEND_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "인증 메일 재발송 대기 중입니다."),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "인증 시도 횟수를 초과했습니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청 횟수를 초과했습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "외부 API 호출에 실패했습니다."),
    EXTERNAL_API_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "외부 API 응답 시간이 초과됐습니다.");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public URI type() {
        String slug = name().toLowerCase(Locale.ROOT).replace('_', '-');
        return URI.create("/errors/" + slug);
    }
}
