package com.runninggu.server.auth.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class RefreshSessionService {

    private final RefreshSessionTransaction transaction;

    public RefreshSessionService(RefreshSessionTransaction transaction) {
        this.transaction = transaction;
    }

    public TokenPair refresh(String refreshToken) {
        return transaction.rotate(refreshToken)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INVALID_REFRESH_TOKEN,
                        "리프레시 토큰이 만료됐거나 무효화됐습니다."));
    }

    public void logout(String refreshToken) {
        transaction.logout(refreshToken);
    }
}
