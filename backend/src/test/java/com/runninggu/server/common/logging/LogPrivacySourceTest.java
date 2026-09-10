package com.runninggu.server.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LogPrivacySourceTest {

    private static final Pattern LOG_STATEMENT = Pattern.compile(
            "log\\.(?:error|warn|info|debug|trace)\\([^;]*\\);",
            Pattern.DOTALL);
    private static final Pattern CATCH_VARIABLE = Pattern.compile(
            "catch\\s*\\(\\s*[\\w.$<>?]+\\s+(\\w+)\\s*\\)");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)\\b(?:[A-Za-z0-9_]*(?:email|latitude|longitude|password|token|authorization)"
                    + "[A-Za-z0-9_]*|lat|lng|coordinates?|requestUri|requestUrl|requestBody|"
                    + "requestHeaders?|responseBody)\\b");

    @Test
    void 애플리케이션_로그는_예외_원문을_직접_출력하지_않는다() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<String> violations = new ArrayList<>();

        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(value -> value.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                List<String> statements = LOG_STATEMENT.matcher(source).results()
                        .map(result -> result.group())
                        .toList();
                for (String statement : statements) {
                    String withoutLiterals = STRING_LITERAL.matcher(statement).replaceAll("\"\"");
                    if (SENSITIVE_VALUE.matcher(withoutLiterals).find()) {
                        violations.add(sourceRoot.relativize(path) + ": 개인정보 값 로그");
                    }
                }

                for (var match : CATCH_VARIABLE.matcher(source).results().toList()) {
                    String variable = Pattern.quote(match.group(1));
                    Pattern safeExceptionValue = Pattern.compile(
                            "\\b" + variable + "\\b\\s*\\.\\s*(?:"
                                    + "getClass\\(\\)\\s*\\.\\s*(?:getName|getSimpleName)\\(\\)|"
                                    + "getStatusCode\\(\\)|reason\\(\\)|errorCode\\(\\))");
                    Pattern remainingExceptionValue = Pattern.compile("\\b" + variable + "\\b");
                    for (String statement : statements) {
                        String sanitized = safeExceptionValue.matcher(statement).replaceAll("");
                        if (remainingExceptionValue.matcher(sanitized).find()) {
                            violations.add(sourceRoot.relativize(path) + ": 예외 원문 로그");
                        }
                    }
                }
            }
        }

        // 예외 message/cause/stack에는 이메일·좌표·토큰이 섞일 수 있다. (SPEC §9.4, NFR-19)
        assertThat(violations)
                .as("개인정보 값 또는 예외 원문을 로그 인자로 넘긴 위치")
                .isEmpty();
    }
}
