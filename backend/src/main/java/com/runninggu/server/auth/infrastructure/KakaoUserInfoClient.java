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

/** 앱이 받은 액세스 토큰의 발급 앱을 검증하고 카카오 회원번호와 동의된 프로필만 조회한다. (SPEC §4.1) */
public class KakaoUserInfoClient implements KakaoUserInfoProvider {

    private static final String ACCESS_TOKEN_INFO_PATH = "/v1/user/access_token_info";
    private static final String USER_INFO_PATH = "/v2/user/me";

    private final RestClient restClient;
    private final long expectedAppId;

    public KakaoUserInfoClient(RestClient restClient, long expectedAppId) {
        this.restClient = restClient;
        this.expectedAppId = expectedAppId;
    }

    @Override
    public KakaoUserProfile retrieve(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            throw new KakaoUserInfoException(Reason.INVALID_TOKEN);
        }

        KakaoAccessTokenInfoResponse tokenInfo = execute(
                ACCESS_TOKEN_INFO_PATH, accessToken, KakaoAccessTokenInfoResponse.class);
        if (tokenInfo == null || tokenInfo.id() == null || tokenInfo.appId() == null) {
            throw new KakaoUserInfoException(Reason.ERROR);
        }
        if (tokenInfo.appId() != expectedAppId) {
            throw new KakaoUserInfoException(Reason.INVALID_TOKEN);
        }

        KakaoUserInfoResponse response =
                execute(USER_INFO_PATH, accessToken, KakaoUserInfoResponse.class);
        if (response == null || response.id() == null) {
            throw new KakaoUserInfoException(Reason.ERROR);
        }
        if (!tokenInfo.id().equals(response.id())) {
            throw new KakaoUserInfoException(Reason.ERROR);
        }

        KakaoUserInfoResponse.KakaoAccount account = response.kakaoAccount();
        String nickname = account == null || account.profile() == null
                ? null
                : textOrNull(account.profile().nickname());
        String email = account == null ? null : textOrNull(account.email());
        return new KakaoUserProfile(response.id().toString(), nickname, email);
    }

    private <T> T execute(String path, String accessToken, Class<T> responseType) {
        try {
            return restClient.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new KakaoUserInfoException(Reason.INVALID_TOKEN, exception);
            }
            throw new KakaoUserInfoException(Reason.ERROR, exception);
        } catch (ResourceAccessException exception) {
            Reason reason = causedByTimeout(exception) ? Reason.TIMEOUT : Reason.ERROR;
            throw new KakaoUserInfoException(reason, exception);
        } catch (RestClientException exception) {
            throw new KakaoUserInfoException(Reason.ERROR, exception);
        }
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
