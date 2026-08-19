package com.runninggu.app.data.remote

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/**
 * Retrofit 호출을 앱이 다루는 실패로 옮긴다. (API 명세 §0-3)
 *
 * Repository 는 이 함수로 감싸고, 화면은 [ApiException] 만 본다.
 * 여기서 실패를 삼키지 않는다 — 조용히 빈 목록을 돌려주면 화면이 Error 를 Empty 로 잘못 그린다.
 */
suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: HttpException) {
    throw e.toApiException()
} catch (e: IOException) {
    throw ApiException.Network(e)
} catch (e: SerializationException) {
    throw ApiException.Malformed(e)
}

/** problem+json 본문을 읽어 [ApiException.Http] 로 만든다. 본문이 깨져도 상태 코드는 살린다. */
fun HttpException.toApiException(): ApiException.Http {
    val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
    return httpErrorOf(code(), body)
}

/**
 * 상태 코드와 본문 문자열로 실패를 만든다. 순수 함수라 테스트가 쉽다.
 *
 * 본문이 problem+json 이 아니거나 비어 있어도 실패를 만들어야 한다 —
 * 프록시·게이트웨이가 HTML 오류 페이지를 돌려주는 경우가 있다.
 */
fun httpErrorOf(status: Int, body: String?): ApiException.Http {
    val problem = body
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ApiJson.decodeFromString(ProblemDetail.serializer(), it) }.getOrNull() }
    return ApiException.Http(
        status = status,
        code = ApiErrorCode.from(problem?.code),
        problem = problem,
    )
}
