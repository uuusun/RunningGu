package com.runninggu.server.auth.application;

import java.util.stream.Stream;

/** 서버 정책과 공개 웹 페이지가 같은 입력 판정표를 사용한다. (SPEC §4.2-2 · NFR-9) */
public final class PasswordPolicyCases {

    public static final String FORMAT_MESSAGE = "8자 이상, 영문과 숫자를 함께 써 주세요.";
    public static final String BYTES_MESSAGE =
            "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.";

    private PasswordPolicyCases() {}

    public static Stream<PasswordCase> validCases() {
        return Stream.of(
                new PasswordCase("최소 8자", "run4life", null),
                new PasswordCase("UTF-8 72바이트 경계", "a1" + "x".repeat(70), null),
                new PasswordCase("보충 문자를 코드포인트로 계산", "a1" + "🏃".repeat(6), null));
    }

    public static Stream<PasswordCase> invalidCases() {
        return Stream.of(
                new PasswordCase("ASCII 영문 없음", "비밀번호1234", FORMAT_MESSAGE),
                new PasswordCase("ASCII 숫자 없음", "password", FORMAT_MESSAGE),
                new PasswordCase("Unicode 숫자만 포함", "a١٢٣٤٥٦٧", FORMAT_MESSAGE),
                new PasswordCase("코드포인트 8자 미만", "a1" + "🏃".repeat(3), FORMAT_MESSAGE),
                new PasswordCase("UTF-8 73바이트", "a1" + "x".repeat(71), BYTES_MESSAGE),
                new PasswordCase("UTF-8 다바이트 상한 초과", "a1" + "가".repeat(24), BYTES_MESSAGE));
    }

    public record PasswordCase(String name, String password, String browserMessage) {
        @Override
        public String toString() {
            return name;
        }
    }
}
