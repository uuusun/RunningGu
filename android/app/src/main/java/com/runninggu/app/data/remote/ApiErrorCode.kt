package com.runninggu.app.data.remote

/**
 * 서버가 내려주는 안정적인 에러 코드. (API 명세 부록 D · NFR-17)
 *
 * 화면 분기는 HTTP 상태가 아니라 **이 코드**로 한다 — 같은 409 라도 "닉네임 중복" 과
 * "대회 블록은 못 바꿈" 은 문구가 다르다.
 *
 * 서버가 새 코드를 추가해도 앱이 깨지지 않도록 모르는 값은 [UNKNOWN] 으로 떨어진다.
 */
enum class ApiErrorCode {
    // 400
    VALIDATION_FAILED,
    INVALID_PASSWORD,
    CURRENT_PASSWORD_MISMATCH,
    INVALID_TRAVEL_PERIOD,
    AGREEMENT_REQUIRED,
    INVALID_CODE,
    CODE_EXPIRED,
    INVALID_RESET_TOKEN,
    BLOCK_SET_MISMATCH,
    INVALID_TRACK,

    // 401
    LOGIN_FAILED,
    UNAUTHORIZED,
    INVALID_KAKAO_TOKEN,
    INVALID_REFRESH_TOKEN,
    REAUTH_FAILED,
    INVALID_REAUTH_TOKEN,

    // 403
    EMAIL_NOT_VERIFIED,
    FORBIDDEN,

    // 404 — `*_NOT_FOUND` 는 리소스마다 다르므로 접미사로 판정한다([isNotFound]).
    NOT_FOUND,

    /** 출발지 검색에 맞는 장소가 없다. 접미사 규칙에 안 걸려 따로 둔다 (§4-4). */
    NO_RESULT,

    // 409
    EMAIL_DUPLICATED,
    NICKNAME_DUPLICATED,
    IDENTITY_ALREADY_LINKED,
    LAST_IDENTITY_REQUIRED,
    EMAIL_IDENTITY_REQUIRED,
    REAUTH_PROVIDER_NOT_LINKED,
    CONTEST_LOCATION_UNAVAILABLE,
    SYSTEM_BLOCK_IMMUTABLE,

    // 429
    SEND_COOLDOWN,
    TOO_MANY_ATTEMPTS,

    // 500 · 502 · 503 · 504
    INTERNAL_SERVER_ERROR,
    EXTERNAL_API_ERROR,
    COURSE_SOURCES_UNAVAILABLE,
    EXTERNAL_API_TIMEOUT,

    /** 서버가 새로 추가했거나 코드가 비어 있는 경우. 일반 오류 문구로 떨어뜨린다. */
    UNKNOWN,
    ;

    companion object {
        /** `CONTEST_NOT_FOUND` 처럼 리소스별로 갈리는 404 는 접미사로 묶는다. */
        private const val NOT_FOUND_SUFFIX = "_NOT_FOUND"

        fun from(raw: String?): ApiErrorCode {
            if (raw.isNullOrBlank()) return UNKNOWN
            if (raw.endsWith(NOT_FOUND_SUFFIX)) return NOT_FOUND
            return entries.firstOrNull { it.name == raw } ?: UNKNOWN
        }
    }
}
