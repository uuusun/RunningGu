package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.MeDto
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/me` 계약. (API 명세 §2 · `screen-api-matrix` A0 · 이슈 #99)
 *
 * 이 응답은 **두 곳이 같이 본다** — 앱 시작 세션 검증(A0)과 마이 화면이다. 그래서 여기가
 * 어긋나면 "로그인이 매번 풀린다" 로 나타난다. 검증이 응답을 못 읽으면
 * `ApiSessionValidator` 가 `Unknown` 으로 떨어뜨려 세션을 지키므로 조용히 넘어가는데,
 * 그러면 A0 이 있으나 마나가 된다.
 *
 * 예시 JSON 은 명세 §2 에 실린 것을 그대로 쓴다.
 */
class MeContractTest {

    /** 명세 §2 의 응답 예시. */
    private val meJson = """
        {
          "id": 1,
          "email": "runner@test.com",
          "nickname": "김러너",
          "loginProvider": "EMAIL",
          "agreements": {
            "tos": true,
            "privacy": true,
            "marketing": false
          },
          "createdAt": "2026-08-23T05:00:00Z"
        }
    """.trimIndent()

    @Test
    fun `내 정보 응답을 그대로 읽는다`() {
        val dto = ApiJson.decodeFromString<MeDto>(meJson)

        assertEquals(1L, dto.id)
        assertEquals("runner@test.com", dto.email)
        assertEquals("김러너", dto.nickname)
        assertEquals("EMAIL", dto.loginProvider)
        assertTrue(dto.agreements.tos)
        assertTrue(dto.agreements.privacy)
        assertEquals(false, dto.agreements.marketing)
    }

    /**
     * 서버는 `createdAt` 을 함께 주는데(§2) 앱은 안 쓴다.
     *
     * **안 쓰는 필드를 DTO 에 받아 두지 않는다.** 대신 모르는 키가 와도 안 깨지는 것이
     * 계약이라(`ApiJson.ignoreUnknownKeys`), 위 예시가 통째로 읽히는 것으로 확인한다.
     * 여기가 깨지면 서버가 필드를 하나 추가할 때마다 로그인이 풀린다.
     */
    @Test
    fun `앱이 안 쓰는 필드가 있어도 읽는다`() {
        val dto = ApiJson.decodeFromString<MeDto>(meJson)

        assertEquals("김러너", dto.nickname)
    }

    @Test
    fun `카카오 가입자는 이메일이 null 일 수 있다`() {
        // 카카오가 이메일을 안 줬을 때다. 별도 입력·인증을 요구하지 않는다 (결정-22 개정)
        val json = meJson
            .replace("\"runner@test.com\"", "null")
            .replace("\"EMAIL\"", "\"KAKAO\"")

        val dto = ApiJson.decodeFromString<MeDto>(json)

        assertNull(dto.email)
        assertEquals("KAKAO", dto.loginProvider)
    }

    /**
     * 필수 필드에 기본값을 두지 않은 것이 여기서 값을 한다. (#89 리뷰)
     *
     * `marketing` 에 기본값 `false` 를 두면 서버가 빠뜨렸을 때 **동의한 사용자에게도 꺼진
     * 것으로 보이고**, 사용자가 토글을 눌러 맞추면 실제로는 철회가 된다.
     */
    @Test
    fun `약관 동의가 빠지면 조용히 통과하지 않는다`() {
        val json = meJson.replace("\"marketing\": false", "\"unused\": false")

        assertThrows(SerializationException::class.java) {
            ApiJson.decodeFromString<MeDto>(json)
        }
    }

    @Test
    fun `닉네임이 빠지면 조용히 통과하지 않는다`() {
        val json = meJson.replace("\"nickname\": \"김러너\",", "")

        assertThrows(SerializationException::class.java) {
            ApiJson.decodeFromString<MeDto>(json)
        }
    }

    @Test
    fun `세션 프로필로 옮긴다`() {
        val profile = ApiJson.decodeFromString<MeDto>(meJson).toSessionProfile()

        assertEquals("김러너", profile.nickname)
        assertEquals("runner@test.com", profile.email)
        assertEquals(LoginProvider.EMAIL, profile.loginProvider)
        // 계정 관리 토글의 초기값이다 — 서버 값을 그대로 들고 온다
        assertEquals(false, profile.marketingAgreed)
    }

    /**
     * 모르는 `loginProvider` 는 기본값으로 바꾸지 않는다.
     *
     * 카카오 가입자를 이메일 가입자로 보이게 하면 계정 화면이 **없는 "비밀번호 변경"** 을
     * 띄운다(§2-1). 계약이 바뀐 것이므로 드러나야 한다.
     */
    @Test
    fun `모르는 가입 수단은 예외로 올린다`() {
        val dto = ApiJson.decodeFromString<MeDto>(meJson.replace("\"EMAIL\"", "\"NAVER\""))

        assertThrows(IllegalArgumentException::class.java) { dto.toSessionProfile() }
    }
}
