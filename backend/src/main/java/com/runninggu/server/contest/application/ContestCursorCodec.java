package com.runninggu.server.contest.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** `(contestDate, id)`를 클라이언트가 해석하지 않는 URL-safe cursor로 바꾼다. (API 명세 §0-4·§3-1) */
@Component
public class ContestCursorCodec {

    private static final String INVALID_CURSOR_DETAIL = "cursor 값이 올바르지 않습니다.";

    public String encode(ContestCursor cursor) {
        String value = cursor.contestDate() + "|" + cursor.contestId();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public ContestCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }

        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(encoded),
                    StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) {
                throw invalidCursor();
            }
            if (!parts[1].matches("[1-9]\\d*")) {
                throw invalidCursor();
            }

            LocalDate contestDate = LocalDate.parse(parts[0]);
            long contestId = Long.parseLong(parts[1]);
            if (contestId <= 0) {
                throw invalidCursor();
            }
            return new ContestCursor(contestDate, contestId);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalidCursor();
        }
    }

    private ApiException invalidCursor() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, INVALID_CURSOR_DETAIL);
    }
}
