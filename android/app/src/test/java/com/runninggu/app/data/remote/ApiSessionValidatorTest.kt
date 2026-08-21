package com.runninggu.app.data.remote

import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionValidation
import com.runninggu.app.data.remote.dto.AgreementsDto
import com.runninggu.app.data.remote.dto.MeDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A0 시작 세션 검증. (`screen-api-matrix` A0 · #89 리뷰)
 *
 * **`401` 이라고 다 죽은 세션이 아니다.** 여기까지 온 `401` 은 [TokenAuthenticator] 가
 * 재발급을 시도한 뒤인데, 그 시도가 만료로 실패했는지 네트워크로 실패했는지에 따라
 * 세션을 죽일지 지킬지가 갈린다. 잘못 가르면 **지하철에서 앱을 켰다고 로그아웃된다.**
 */
class ApiSessionValidatorTest {

    private val profile = MeDto(
        id = 1,
        email = "runner@test.com",
        nickname = "김러너",
        loginProvider = "EMAIL",
        agreements = AgreementsDto(tos = true, privacy = true, marketing = false),
    )

    private fun unauthorized() = ApiException.Http(
        status = 401,
        code = ApiErrorCode.UNAUTHORIZED,
        problem = null,
    )

    @Test
    fun `me 401 이고 재발급도 만료면 세션이 죽은 것이다`() = runBlocking {
        // 재발급이 만료로 끝나면 TokenAuthenticator 가 이미 세션을 비운다 (#74)
        val validator = ApiSessionValidator(
            me = { throw unauthorized() },
            isSignedOut = { true },
        )

        assertEquals(SessionValidation.Expired, validator.validate())
    }

    @Test
    fun `me 401 이어도 재발급이 네트워크로 실패했으면 세션을 지킨다`() = runBlocking {
        // 세션이 남아 있다는 건 인증자가 포기하지 않았다는 뜻이다
        val validator = ApiSessionValidator(
            me = { throw unauthorized() },
            isSignedOut = { false },
        )

        assertEquals(SessionValidation.Unknown, validator.validate())
    }

    @Test
    fun `재발급 뒤 조회가 성공하면 서버 프로필을 준다`() = runBlocking {
        val validator = ApiSessionValidator(me = { profile }, isSignedOut = { false })

        val result = validator.validate()

        assertTrue(result is SessionValidation.Valid)
        assertEquals("김러너", (result as SessionValidation.Valid).profile.nickname)
        assertEquals(LoginProvider.EMAIL, result.profile.loginProvider)
    }

    @Test
    fun `네트워크 오류는 세션을 죽이지 않는다`() = runBlocking {
        val validator = ApiSessionValidator(
            me = { throw ApiException.Network(IOException("끊김")) },
            isSignedOut = { false },
        )

        assertEquals(SessionValidation.Unknown, validator.validate())
    }

    @Test
    fun `서버 오류도 세션을 죽이지 않는다`() = runBlocking {
        // 5xx 는 서버 사정이지 사용자 세션 문제가 아니다
        val validator = ApiSessionValidator(
            me = { throw ApiException.Http(500, ApiErrorCode.INTERNAL_SERVER_ERROR, null) },
            isSignedOut = { false },
        )

        assertEquals(SessionValidation.Unknown, validator.validate())
    }

    @Test
    fun `모르는 loginProvider 는 계약 문제지 세션 문제가 아니다`() = runBlocking {
        val validator = ApiSessionValidator(
            me = { profile.copy(loginProvider = "GOOGLE") },
            isSignedOut = { false },
        )

        assertEquals(SessionValidation.Unknown, validator.validate())
    }

    @Test
    fun `제한 시간을 넘기면 기다리지 않고 넘어간다`() = runBlocking {
        // 시작 화면이 이 결과를 기다린다. 오프라인에서 앱이 멈춘 것처럼 보이면 안 된다
        // 제한 시간을 짧게 줄여 테스트가 실제로 기다리지 않게 한다
        val validator = ApiSessionValidator(
            me = {
                delay(SHORT_TIMEOUT_MS * 4)
                profile
            },
            isSignedOut = { false },
            timeoutMs = SHORT_TIMEOUT_MS,
        )

        assertEquals(SessionValidation.Unknown, validator.validate())
    }

    private companion object {
        const val SHORT_TIMEOUT_MS = 50L
    }
}
