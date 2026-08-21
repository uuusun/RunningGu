package com.runninggu.server.auth.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class EmailNormalizer {

    private static final int MAX_EMAIL_CODE_POINTS = 320;
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,}$");

    /** 이메일 정규화는 서버 한 곳에서 수행한다. (SPEC §4.2, 결정-50) */
    public String normalize(String email) {
        String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints > MAX_EMAIL_CODE_POINTS || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "email 값의 형식을 확인해 주세요.");
        }
        return normalized;
    }
}
