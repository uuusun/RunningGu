package com.runninggu.server.contest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ContestCursorCodecTest {

    private final ContestCursorCodec codec = new ContestCursorCodec();

    @Test
    void 명세의_URL_safe_Base64_형식으로_왕복한다() {
        ContestCursor cursor = new ContestCursor(LocalDate.of(2026, 8, 22), 153L);

        String encoded = codec.encode(cursor);

        assertThat(encoded).isEqualTo("MjAyNi0wOC0yMnwxNTM");
        assertThat(codec.decode(encoded)).isEqualTo(cursor);
    }

    @Test
    void 빈_cursor는_첫_페이지로_해석한다() {
        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode(" ")).isNull();
    }

    @Test
    void 변조되거나_형식이_틀린_cursor를_거부한다() {
        String invalidDate = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("not-a-date|3".getBytes(StandardCharsets.UTF_8));
        String invalidId = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("2026-08-22|0".getBytes(StandardCharsets.UTF_8));

        assertInvalid("%%%invalid%%%");
        assertInvalid(invalidDate);
        assertInvalid(invalidId);
    }

    private void assertInvalid(String cursor) {
        assertThatThrownBy(() -> codec.decode(cursor))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
