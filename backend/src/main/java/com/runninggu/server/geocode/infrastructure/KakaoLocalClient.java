package com.runninggu.server.geocode.infrastructure;

import com.runninggu.server.geocode.application.GeocodeProvider;
import com.runninggu.server.geocode.application.GeocodeProviderException;
import com.runninggu.server.geocode.application.GeocodeProviderException.Reason;
import com.runninggu.server.geocode.domain.GeocodeResult;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 카카오 로컬 키워드 검색을 서버 키로 호출한다. (SPEC §7.4, API 명세 §4-4) */
public class KakaoLocalClient implements GeocodeProvider {

    private static final String KEYWORD_SEARCH_PATH = "/v2/local/search/keyword.json";
    private static final int MAX_ATTEMPTS = 2;

    private final RestClient restClient;
    private final String restKey;

    public KakaoLocalClient(RestClient restClient, String restKey) {
        this.restClient = restClient;
        this.restKey = restKey;
    }

    @Override
    public Optional<GeocodeResult> findFirst(String query) {
        if (!StringUtils.hasText(restKey)) {
            throw new GeocodeProviderException(Reason.ERROR);
        }

        KakaoKeywordSearchResponse response = executeWithRateLimitRetry(query);
        List<KakaoKeywordSearchResponse.Document> documents = response.documents();
        if (documents == null || documents.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toResult(documents.getFirst()));
    }

    private KakaoKeywordSearchResponse executeWithRateLimitRetry(String query) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                KakaoKeywordSearchResponse response = restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path(KEYWORD_SEARCH_PATH)
                                .queryParam("query", query)
                                .queryParam("size", 1)
                                .build())
                        .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restKey)
                        .retrieve()
                        .body(KakaoKeywordSearchResponse.class);
                if (response == null) {
                    throw new GeocodeProviderException(Reason.ERROR);
                }
                return response;
            } catch (RestClientResponseException exception) {
                if (exception.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                        && attempt < MAX_ATTEMPTS) {
                    continue;
                }
                throw new GeocodeProviderException(Reason.ERROR, exception);
            } catch (ResourceAccessException exception) {
                Reason reason = causedByTimeout(exception) ? Reason.TIMEOUT : Reason.ERROR;
                throw new GeocodeProviderException(reason, exception);
            } catch (RestClientException exception) {
                throw new GeocodeProviderException(Reason.ERROR, exception);
            }
        }
        throw new GeocodeProviderException(Reason.ERROR);
    }

    private GeocodeResult toResult(KakaoKeywordSearchResponse.Document document) {
        String name = textOrNull(document.placeName());
        if (name == null) {
            throw new GeocodeProviderException(Reason.ERROR);
        }

        String address = textOrNull(document.roadAddressName());
        if (address == null) {
            address = textOrNull(document.addressName());
        }

        try {
            BigDecimal lng = new BigDecimal(document.x());
            BigDecimal lat = new BigDecimal(document.y());
            return new GeocodeResult(name, address == null ? "" : address, lat, lng);
        } catch (RuntimeException exception) {
            throw new GeocodeProviderException(Reason.ERROR, exception);
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
