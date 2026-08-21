package com.runninggu.app.data.repository

import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.AuthApi
import com.runninggu.app.data.remote.dto.AuthTokenResponseDto
import com.runninggu.app.data.remote.dto.ExistsResponseDto
import com.runninggu.app.data.remote.dto.KakaoLoginRequestDto
import com.runninggu.app.data.remote.dto.KakaoLoginResponseDto
import com.runninggu.app.data.remote.dto.KakaoSignupRequestDto
import com.runninggu.app.data.remote.dto.LoginRequestDto
import com.runninggu.app.data.remote.dto.LogoutRequestDto
import com.runninggu.app.data.remote.dto.ResetPasswordRequestDto
import com.runninggu.app.data.remote.dto.ResetRequestDto
import com.runninggu.app.data.remote.dto.SendCodeRequestDto
import com.runninggu.app.data.remote.dto.SignupRequestDto
import com.runninggu.app.data.remote.dto.VerifyCodeRequestDto
import com.runninggu.app.data.remote.dto.VerifyCodeResponseDto
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Retrofit 이 던지는 것을 `Result` 로 담는가. (#106 리뷰)
 *
 * **Retrofit 은 `ApiException` 을 던지지 않는다** — 비 2xx 는 `HttpException`, 통신 실패는
 * `IOException` 이다. 이것들이 `Result` 로 안 담기면 호출부(`viewModelScope.launch`)에서
 * 잡히지 않은 예외가 되어 **앱이 죽는다.** 문구가 안 뜨는 정도가 아니다.
 */
class RemoteAuthRepositoryTest {

    private class ThrowingApi(private val error: Throwable) : AuthApi {
        override suspend fun login(body: LoginRequestDto): AuthTokenResponseDto = throw error

        override suspend fun emailExists(email: String): ExistsResponseDto = TODO()
        override suspend fun nicknameExists(nickname: String): ExistsResponseDto = TODO()
        override suspend fun sendSignupCode(body: SendCodeRequestDto) = TODO()
        override suspend fun verifySignupCode(body: VerifyCodeRequestDto): VerifyCodeResponseDto = TODO()
        override suspend fun signup(body: SignupRequestDto): AuthTokenResponseDto = TODO()
        override suspend fun kakaoLogin(body: KakaoLoginRequestDto): KakaoLoginResponseDto = TODO()
        override suspend fun kakaoSignup(body: KakaoSignupRequestDto): AuthTokenResponseDto = TODO()
        override suspend fun logout(body: LogoutRequestDto) = TODO()
        override suspend fun requestPasswordReset(body: ResetRequestDto) = TODO()
        override suspend fun resetPassword(body: ResetPasswordRequestDto) = TODO()
    }

    private fun httpError(code: Int, body: String) = HttpException(
        Response.error<Unit>(code, body.toResponseBody("application/problem+json".toMediaType())),
    )

    @Test
    fun `비밀번호가 틀리면 앱이 죽지 않고 실패로 담긴다`() = runBlocking {
        // 감싸지 않으면 여기서 HttpException 이 그대로 올라가 테스트가 예외로 끝난다.
        val api = ThrowingApi(
            httpError(401, """{"code":"LOGIN_FAILED","userMessage":"이메일 또는 비밀번호가 올바르지 않아요."}"""),
        )

        val result = RemoteAuthRepository(api).login("runner@test.com", "wrong")

        assertTrue("실패로 담기지 않았다", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("ApiException 이 아니다: $error", error is ApiException.Http)
        assertEquals(ApiErrorCode.LOGIN_FAILED, (error as ApiException.Http).code)
    }

    @Test
    fun `통신이 끊겨도 앱이 죽지 않고 실패로 담긴다`() = runBlocking {
        val api = ThrowingApi(IOException("네트워크 없음"))

        val result = RemoteAuthRepository(api).login("runner@test.com", "pw")

        assertTrue("실패로 담기지 않았다", result.isFailure)
        assertTrue(
            "Network 로 안 바뀌었다: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is ApiException.Network,
        )
    }
}
