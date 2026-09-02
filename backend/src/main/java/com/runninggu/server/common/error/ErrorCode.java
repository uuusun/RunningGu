package com.runninggu.server.common.error;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** 앱이 HTTP 문구가 아니라 안정적인 code 값으로 오류를 분기하도록 한다. (SPEC §9.3) */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    BLOCK_SET_MISMATCH(HttpStatus.BAD_REQUEST, "블록 목록이 올바르지 않습니다."),
    INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "비밀번호 형식이 올바르지 않습니다."),
    INVALID_RESET_TOKEN(HttpStatus.BAD_REQUEST, "비밀번호 재설정 토큰이 올바르지 않습니다."),
    CURRENT_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다."),
    INVALID_TRAVEL_PERIOD(HttpStatus.BAD_REQUEST, "여행 기간이 올바르지 않습니다."),
    AGREEMENT_REQUIRED(HttpStatus.BAD_REQUEST, "필수 약관 동의가 필요합니다."),
    AGE_REQUIREMENT_NOT_MET(HttpStatus.BAD_REQUEST, "만 14세 이상만 가입할 수 있습니다."),
    INVALID_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않습니다."),
    CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증 코드가 만료됐습니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호를 확인해 주세요."),
    REAUTH_FAILED(HttpStatus.UNAUTHORIZED, "재인증에 실패했습니다."),
    INVALID_REAUTH_TOKEN(HttpStatus.UNAUTHORIZED, "재인증 토큰이 올바르지 않습니다."),
    INVALID_KAKAO_TOKEN(HttpStatus.UNAUTHORIZED, "카카오 액세스 토큰이 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "이메일 인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    CONTEST_NOT_FOUND(HttpStatus.NOT_FOUND, "대회를 찾을 수 없습니다."),
    ITINERARY_NOT_FOUND(HttpStatus.NOT_FOUND, "동선을 찾을 수 없습니다."),
    SAVED_COURSE_NOT_FOUND(HttpStatus.NOT_FOUND, "저장 코스를 찾을 수 없습니다."),
    DAY_NOT_FOUND(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다."),
    BLOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "블록을 찾을 수 없습니다."),
    NO_RESULT(HttpStatus.NOT_FOUND, "검색 결과를 찾을 수 없습니다."),
    EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    KAKAO_ACCOUNT_DUPLICATED(HttpStatus.CONFLICT, "이미 가입된 카카오 계정입니다."),
    CONTEST_LOCATION_UNAVAILABLE(HttpStatus.CONFLICT, "대회장 위치를 확인할 수 없습니다."),
    CONTEST_INACTIVE(HttpStatus.CONFLICT, "정보 제공이 종료된 대회입니다."),
    SYSTEM_BLOCK_IMMUTABLE(HttpStatus.CONFLICT, "변경할 수 없는 일정입니다."),
    NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    EMAIL_IDENTITY_REQUIRED(HttpStatus.CONFLICT, "이메일 로그인 계정에서만 사용할 수 있습니다."),
    REAUTH_PROVIDER_MISMATCH(HttpStatus.CONFLICT, "가입한 로그인 방식으로 재인증해야 합니다."),
    SEND_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "인증 메일 재발송 대기 중입니다."),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "인증 시도 횟수를 초과했습니다."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청 횟수를 초과했습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    COURSE_SOURCES_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "주변 코스 원천을 사용할 수 없습니다."),
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
