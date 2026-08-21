package com.runninggu.server.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

class NicknamePolicyTest {

    private final NicknamePolicy policy = new NicknamePolicy();

    @Test
    void 표시값은_양끝만_제거하고_내부_공백과_Unicode를_보존한다() {
        assertThat(policy.normalizeDisplay("  달리는 🏃 사람  "))
                .isEqualTo("달리는 🏃 사람");
    }

    @Test
    void 길이는_UTF16이_아닌_Unicode_코드포인트로_계산한다() {
        assertThat(policy.normalizeDisplay("🏃🏃"))
                .isEqualTo("🏃🏃");
        assertInvalid("🏃");
        assertInvalid("가".repeat(13));
    }

    @Test
    void 중복키는_ASCII_대문자만_소문자로_접는다() {
        assertThat(policy.duplicateKey("Run닝Gu"))
                .isEqualTo("run닝gu");
        assertThat(policy.duplicateKey("ÄBC"))
                .isEqualTo("Äbc");
    }

    @Test
    void Unicode_정규화는_하지_않는다() {
        assertThat(policy.duplicateKey("é러너"))
                .isNotEqualTo(policy.duplicateKey("e\u0301러너"));
    }

    private void assertInvalid(String value) {
        assertThatThrownBy(() -> policy.normalizeDisplay(value))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
