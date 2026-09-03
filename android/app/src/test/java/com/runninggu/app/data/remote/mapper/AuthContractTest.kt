package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.AgreementsRequestDto
import com.runninggu.app.data.remote.dto.AuthTokenResponseDto
import com.runninggu.app.data.remote.dto.ExistsResponseDto
import com.runninggu.app.data.remote.dto.KakaoLoginResponseDto
import com.runninggu.app.data.remote.dto.SignupRequestDto
import com.runninggu.app.data.remote.dto.VerifyCodeResponseDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 인증 API 계약. (API 명세 §1)
 *
 * **서버 구현이 아직 없으므로 명세에 실린 예시 JSON 을 그대로** 넣어 고정한다. 계약이
 * 바뀌면 이 테스트가 먼저 깨져야 한다 — 대회 API 를 같은 방식으로 만들어 뒀고, #84 가
 * 실제로 섰을 때 한 글자도 안 고치고 붙었다.
 */
class AuthContractTest {

    /** 명세 §1-6 의 응답 예시. */
    private val loginJson = """
        {
          "accessToken": "eyJhbGciOi...",
          "refreshToken": "eyJhbGciOi...",
          "user": {
            "id": 1,
            "email": "runner@test.com",
            "nickname": "김러너",
            "loginProvider": "EMAIL"
          }
        }
    """.trimIndent()

    @Test
    fun `로그인 응답을 그대로 읽는다`() {
        val dto = ApiJson.decodeFromString<AuthTokenResponseDto>(loginJson)

        assertEquals("eyJhbGciOi...", dto.accessToken)
        assertEquals("eyJhbGciOi...", dto.refreshToken)
        assertEquals(1L, dto.user.id)
        assertEquals("runner@test.com", dto.user.email)
        assertEquals("김러너", dto.user.nickname)
        assertEquals("EMAIL", dto.user.loginProvider)
    }

    @Test
    fun `가입 응답은 로그인과 같은 모양이다`() {
        // 명세가 "1-6 과 동일 응답" 이라고 못 박았다 (§1-5 · §1-8)
        val dto = ApiJson.decodeFromString<AuthTokenResponseDto>(loginJson)

        assertTrue(dto.accessToken.isNotBlank())
    }

    @Test
    fun `카카오 이메일이 없어도 읽는다`() {
        // 카카오가 이메일 동의를 안 받았으면 null 이다 (§1-7 · §2 · #59)
        val json = loginJson.replace("\"runner@test.com\"", "null")
            .replace("\"EMAIL\"", "\"KAKAO\"")

        val dto = ApiJson.decodeFromString<AuthTokenResponseDto>(json)

        assertNull(dto.user.email)
        assertEquals("KAKAO", dto.user.loginProvider)
    }

    @Test
    fun `필수 필드가 빠지면 조용히 통과하지 않는다`() {
        // 기본값을 두면 토큰 없는 응답이 "로그인 성공" 으로 지나간다
        val json = """{"refreshToken": "r", "user": {"id": 1, "nickname": "n", "loginProvider": "EMAIL"}}"""

        assertThrows(SerializationException::class.java) {
            ApiJson.decodeFromString<AuthTokenResponseDto>(json)
        }
    }

    @Test
    fun `카카오 미가입 응답을 읽는다`() {
        // 같은 200 인데 모양이 둘이다 — isNewUser 로 가른다 (§1-7)
        val json = """
            {"isNewUser": true, "kakaoProfile": {"nickname": "카카오프로필명", "email": null}}
        """.trimIndent()

        val dto = ApiJson.decodeFromString<KakaoLoginResponseDto>(json)

        assertTrue(dto.isNewUser)
        assertNull(dto.accessToken)
        assertEquals("카카오프로필명", dto.kakaoProfile?.nickname)
        assertNull(dto.kakaoProfile?.email)
    }

    @Test
    fun `중복 확인 응답을 읽는다`() {
        assertTrue(ApiJson.decodeFromString<ExistsResponseDto>("""{"exists": true}""").exists)
    }

    @Test
    fun `코드 검증 응답에 기본값을 두지 않는다`() {
        // verified 가 빠졌는데 false 로 통과하면 "인증 실패" 로 보인다
        assertThrows(SerializationException::class.java) {
            ApiJson.decodeFromString<VerifyCodeResponseDto>("{}")
        }
        assertTrue(ApiJson.decodeFromString<VerifyCodeResponseDto>("""{"verified":true}""").verified)
    }

    @Test
    fun `가입 요청이 명세 예시대로 나간다`() {
        val body = SignupRequestDto(
            email = "runner@test.com",
            password = "run4life1",
            nickname = "김러너",
            agreements = AgreementsRequestDto(tos = true, privacy = true, marketing = false),
            ageOver14 = true
        )

        val json = ApiJson.encodeToString(body)

        assertTrue(json.contains("\"email\":\"runner@test.com\""))
        assertTrue(json.contains("\"nickname\":\"김러너\""))
        // 필수 2종은 반드시 실려야 한다 — 빠지면 400 AGREEMENT_REQUIRED
        assertTrue(json.contains("\"tos\":true"))
        assertTrue(json.contains("\"privacy\":true"))
    }
}
