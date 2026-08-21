package com.runninggu.app.data.remote

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
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 인증. (API 명세 §1)
 *
 * **전부 공개 API 다** — 토큰을 얻기 전에 부르는 것들이라 `Authorization` 이 필요 없다.
 * 로그아웃(§1-10)만 리프레시 토큰을 본문에 실어 보낸다.
 *
 * 토큰 재발급(§1-9)은 [TokenApi] 에 따로 있다. 재발급은 `401` 을 처리하는 경로라
 * `Authenticator` 가 달리지 않은 클라이언트로 불러야 하기 때문이다(#74).
 */
interface AuthApi {

    /**
     * 이메일 중복 확인. (§1-1)
     *
     * 가입 2단계 인라인 검증용이다. 발송·가입의 `409` 는 동시 요청에 대비한 최종 방어로
     * 그대로 둔다(이슈 #97 합의).
     */
    @GET("auth/email/exists")
    suspend fun emailExists(@Query("email") email: String): ExistsResponseDto

    /** 닉네임 중복 확인. 대소문자를 무시해 판정하는 건 서버다. (§1-2 · 이슈 #97) */
    @GET("auth/nickname/exists")
    suspend fun nicknameExists(@Query("nickname") nickname: String): ExistsResponseDto

    /**
     * 인증 코드 발송. `204`. (§1-3)
     *
     * 오류로 `409 EMAIL_DUPLICATED`(이미 가입) · `429 SEND_COOLDOWN`(60초) 이 온다.
     */
    @POST("auth/email/send-code")
    suspend fun sendSignupCode(@Body body: SendCodeRequestDto)

    /**
     * 인증 코드 검증. (§1-4)
     *
     * 오류로 `400 INVALID_CODE` · `400 CODE_EXPIRED` · `429 TOO_MANY_ATTEMPTS` 가 온다.
     * 화면은 뒤 둘에서 입력을 잠그고 재발송을 유도한다(#60).
     */
    @POST("auth/email/verify")
    suspend fun verifySignupCode(@Body body: VerifyCodeRequestDto): VerifyCodeResponseDto

    /** 이메일 가입. `201` 이고 응답이 로그인과 같다 — **자동 로그인**이다. (§1-5) */
    @POST("auth/signup")
    suspend fun signup(@Body body: SignupRequestDto): AuthTokenResponseDto

    /**
     * 이메일 로그인. (§1-6)
     *
     * 실패는 `401 LOGIN_FAILED` 하나다 — 이메일이 없는 건지 비밀번호가 틀린 건지
     * **구분해서 알리지 않는다**(§4.1 계정 존재 비노출).
     */
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequestDto): AuthTokenResponseDto

    /** 카카오 로그인. 미가입이면 `isNewUser=true` 로 온다. (§1-7) */
    @POST("auth/kakao")
    suspend fun kakaoLogin(@Body body: KakaoLoginRequestDto): KakaoLoginResponseDto

    /** 카카오 가입. 이메일 인증을 생략한다. (§1-8) */
    @POST("auth/kakao/signup")
    suspend fun kakaoSignup(@Body body: KakaoSignupRequestDto): AuthTokenResponseDto

    /** 로그아웃 — 해당 리프레시 revoke. `204`. (§1-10) */
    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequestDto)

    /**
     * 비밀번호 재설정 메일 요청. (§1-11)
     *
     * **가입 여부와 무관하게 `202`** 다. 화면도 "가입된 이메일이면 보냈어요" 로 말한다 —
     * 성공·실패를 가르면 계정 존재가 드러난다(§4.3).
     */
    @POST("auth/password/reset-request")
    suspend fun requestPasswordReset(@Body body: ResetRequestDto)

    /**
     * 새 비밀번호 설정. `204`. (§1-12)
     *
     * P0 에서 이 화면은 **서버가 서빙하는 웹 페이지**다(§4.3). 앱이 부를 일은 없지만,
     * 딥링크로 앱 안에서 처리하게 되면 여기를 쓴다.
     */
    @POST("auth/password/reset")
    suspend fun resetPassword(@Body body: ResetPasswordRequestDto)
}
