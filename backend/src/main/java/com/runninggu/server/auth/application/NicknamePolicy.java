package com.runninggu.server.auth.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class NicknamePolicy {

    private static final int MIN_CODE_POINTS = 2;
    private static final int MAX_CODE_POINTS = 12;

    public String normalizeDisplay(String nickname) {
        String normalized = nickname == null ? "" : nickname.strip();
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints < MIN_CODE_POINTS || codePoints > MAX_CODE_POINTS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "nickname 값은 2~12자여야 합니다.");
        }
        return normalized;
    }

    /** 표시 표기는 보존하고 ASCII 영문만 소문자로 접어 중복 키를 만든다. (SPEC 결정-50) */
    public String duplicateKey(String nickname) {
        String display = normalizeDisplay(nickname);
        StringBuilder key = new StringBuilder(display.length());
        display.codePoints().forEach(codePoint -> {
            int folded = codePoint >= 'A' && codePoint <= 'Z'
                    ? codePoint + ('a' - 'A')
                    : codePoint;
            key.appendCodePoint(folded);
        });
        return key.toString();
    }
}
