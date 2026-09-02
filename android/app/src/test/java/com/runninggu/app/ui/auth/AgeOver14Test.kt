package com.runninggu.app.ui.auth

import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.AgreementsRequestDto
import com.runninggu.app.data.remote.dto.KakaoSignupRequestDto
import com.runninggu.app.data.remote.dto.SignupRequestDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 만 14세 이상 확인. (SPEC §4.2-1 · 결정-58 · 이슈 #228)
 *
 * 서버가 `ageOver14` 를 **최상위 필수 필드**로 받는다(#263). 누락은
 * `400 VALIDATION_FAILED`, `false` 는 `400 AGE_REQUIREMENT_NOT_MET` 이다.
 *
 * 여기서 잠그는 것은 셋이다.
 *
 * 1. 요청 본문에 `ageOver14` 가 **최상위로** 실린다 — `agreements` 안이 아니다
 * 2. **전체 동의가 이 값을 건드리지 않는다** — 명세가 "전체 동의로 자동 선택하지 않는다"
 * 3. 확인하지 않으면 **다음 단계로 못 간다**
 *
 * # 망가뜨리면 이것만 실패한다
 * ```
 * ① onToggleAll 이 ageOver14 도 켜게 한다  →  2개 실패
 *      전체 동의는 만 14세 확인을 건드리지 않는다 FAILED
 *      만 14세를 확인하지 않으면 다음으로 못 간다 FAILED
 *
 *    둘째가 같이 깨지는 것이 맞다 — 전체 동의가 이 값을 켜면 그 다음 줄의
 *    "필수 2종만으로 넘어가면 안 된다" 가 이미 참이 아니게 된다
 *
 * ② canProceedAgree 에서 `&& ageOver14` 를 뺀다  →  1개 실패
 *      만 14세를 확인하지 않으면 다음으로 못 간다 FAILED
 *
 * ③ SignupRequestDto 의 ageOver14 를 agreements 안으로 옮긴다  →  1개 실패
 *      이메일 가입 요청은 ageOver14 를 최상위로 싣는다 FAILED
 * ```
 */
class AgeOver14Test {

    private val agreements = AgreementsRequestDto(tos = true, privacy = true, marketing = false)

    @Test
    fun `이메일 가입 요청은 ageOver14 를 최상위로 싣는다`() {
        val body = SignupRequestDto(
            email = "runner@test.com",
            password = "run4life1",
            nickname = "김러너",
            agreements = agreements,
            ageOver14 = true,
        )

        val json = Json.parseToJsonElement(
            ApiJson.encodeToString(SignupRequestDto.serializer(), body),
        ).jsonObject

        assertEquals(true, json["ageOver14"]?.jsonPrimitive?.content?.toBoolean())
        // `agreements` 안에 있으면 서버가 최상위에서 못 찾아 400 이다
        assertNull("agreements 안에 들어갔다", json["agreements"]?.jsonObject?.get("ageOver14"))
    }

    @Test
    fun `카카오 가입 요청도 같은 자리에 싣는다`() {
        val body = KakaoSignupRequestDto(
            kakaoAccessToken = "T",
            nickname = "김러너",
            agreements = agreements,
            ageOver14 = true,
        )

        val json = Json.parseToJsonElement(
            ApiJson.encodeToString(KakaoSignupRequestDto.serializer(), body),
        ).jsonObject

        assertEquals(true, json["ageOver14"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `확인하지 않으면 false 가 그대로 나간다`() {
        // 기본값으로 true 를 채우면 앱이 사용자 대신 답하는 것이 된다.
        // 서버가 400 AGE_REQUIREMENT_NOT_MET 으로 막을 수 있어야 한다
        val body = SignupRequestDto(
            email = "runner@test.com",
            password = "run4life1",
            nickname = "김러너",
            agreements = agreements,
            ageOver14 = false,
        )

        val json = Json.parseToJsonElement(
            ApiJson.encodeToString(SignupRequestDto.serializer(), body),
        ).jsonObject

        assertEquals(false, json["ageOver14"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `전체 동의는 만 14세 확인을 건드리지 않는다`() {
        val vm = SignupViewModel(com.runninggu.app.data.repository.FakeAuthRepository)

        vm.onToggleAll()
        val state = vm.uiState.value

        assertTrue("전체 동의가 필수 2종을 켜야 한다", state.tosAgreed && state.privacyAgreed)
        assertTrue("전체 동의가 마케팅도 켜야 한다", state.marketingAgreed)
        // SPEC §4.2-1 — "전체 동의로 자동 선택하지 않는다"
        assertFalse("전체 동의가 만 14세 확인까지 켰다", state.ageOver14)
    }

    @Test
    fun `만 14세를 확인하지 않으면 다음으로 못 간다`() {
        val vm = SignupViewModel(com.runninggu.app.data.repository.FakeAuthRepository)

        vm.onToggleAll()
        assertFalse("필수 2종만으로 넘어가면 안 된다", vm.uiState.value.canProceedAgree)

        vm.onToggleAgeOver14()
        assertTrue("셋을 다 켜면 넘어간다", vm.uiState.value.canProceedAgree)
    }
}
