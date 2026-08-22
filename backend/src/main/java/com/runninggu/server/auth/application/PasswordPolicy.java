package com.runninggu.server.auth.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {

    private static final int MIN_CODE_POINTS = 8;
    private static final int MAX_UTF8_BYTES = 72;
    private static final Pattern ASCII_LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    /** BCrypt의 입력 절단을 막기 위해 UTF-8 72바이트를 상한으로 검증한다. (SPEC NFR-9) */
    public void validate(String password) {
        int codePoints = password == null ? 0 : password.codePointCount(0, password.length());
        int utf8Bytes = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (codePoints < MIN_CODE_POINTS
                || utf8Bytes > MAX_UTF8_BYTES
                || !ASCII_LETTER.matcher(password == null ? "" : password).find()
                || !DIGIT.matcher(password == null ? "" : password).find()) {
            throw new ApiException(
                    ErrorCode.INVALID_PASSWORD,
                    "비밀번호는 8자 이상 영문과 숫자를 포함하고 UTF-8 72바이트 이하여야 합니다.");
        }
    }

    public boolean canVerify(String password) {
        if (password == null) {
            return false;
        }
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }
}
