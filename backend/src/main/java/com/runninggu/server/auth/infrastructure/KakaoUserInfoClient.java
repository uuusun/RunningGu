package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.application.KakaoUserInfoException;
import com.runninggu.server.auth.application.KakaoUserInfoException.Reason;
import com.runninggu.server.auth.application.KakaoUserInfoProvider;
import com.runninggu.server.auth.application.KakaoUserProfile;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 앱이 받은 액세스 토큰으로 카카오 회원번호와 동의된 프로필만 조회한다. (SPEC §4.1) */
public class KakaoUserInfoClient implements KakaoUserInfoProvider {

    private static final String USER_INFO_PATH = "/v2/user/me";
    private static final int MAX_ATTEMPTS = 2;

    private final RestClient restClient;

    public KakaoUserInfoClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public KakaoUserProfile retrieve(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            throw new KakaoUserInfoException(Reason.INVALID_TOKEN);
        }

        KakaoUserInfoResponse response = executeWithRateLimitRetry(accessToken);
        if (response == null || response.id() == null) {
            throw new KakaoUserInfoException(Reason.ERROR);
        }

        KakaoUserInfoResponse.KakaoAccount account = response.kakaoAccount();
        String nickname = account == null || account.profile() == null
                ? null
                : textOrNull(account.profile().nickname());
        String email = account == null ? null : textOrNull(account.email());
        return new KakaoUserProfile(response.id().toString(), nickname, email);
    }

    private KakaoUserInfoResponse executeWithRateLimitRetry(String accessToken) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.get()
                        .uri(USER_INFO_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .retrieve()
                        .body(KakaoUserInfoResponse.class);
            } catch (RestClientResponseException exception) {
                if (exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                    throw new KakaoUserInfoException(Reason.INVALID_TOKEN, exception);
                }
                if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                        && attempt < MAX_ATTEMPTS) {
                    continue;
                }
                throw new KakaoUserInfoException(Reason.ERROR, exception);
            } catch (ResourceAccessException exception) {
                Reason reason = causedByTimeout(exception) ? Reason.TIMEOUT : Reason.ERROR;
                throw new KakaoUserInfoException(reason, exception);
            } catch (RestClientException exception) {
                throw new KakaoUserInfoException(Reason.ERROR, exception);
            }
        }
        throw new KakaoUserInfoException(Reason.ERROR);
    }

    private String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private boolean causedByTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
