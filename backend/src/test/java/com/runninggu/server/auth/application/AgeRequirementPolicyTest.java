package com.runninggu.server.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

class AgeRequirementPolicyTest {

    private final AgeRequirementPolicy policy = new AgeRequirementPolicy();

    @Test
    void 만_14세_이상이면_가입검증을_통과한다() {
        assertThatCode(() -> policy.validate(true)).doesNotThrowAnyException();
    }

    @Test
    void 만_14세_이상_확인이_false면_계약오류로_거부한다() {
        assertThatThrownBy(() -> policy.validate(false))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.errorCode())
                            .isEqualTo(ErrorCode.AGE_REQUIREMENT_NOT_MET);
                    assertThat(exception.getMessage())
                            .isEqualTo("만 14세 이상만 가입할 수 있습니다.");
                });
    }
}
