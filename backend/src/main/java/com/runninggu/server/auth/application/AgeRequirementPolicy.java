package com.runninggu.server.auth.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class AgeRequirementPolicy {

    /** 생년월일을 수집하지 않고 가입 요청의 만 14세 이상 확인만 검증한다. (SPEC §4.2, 결정-58) */
    public void validate(boolean ageOver14) {
        if (!ageOver14) {
            throw new ApiException(ErrorCode.AGE_REQUIREMENT_NOT_MET);
        }
    }
}
