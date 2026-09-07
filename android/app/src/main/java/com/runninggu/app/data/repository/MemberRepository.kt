package com.runninggu.app.data.repository

import com.runninggu.app.data.local.AuthTokens
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.remote.MeApi
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.remote.dto.PasswordChangeRequest
import com.runninggu.app.data.remote.dto.ReauthRequest
import com.runninggu.app.data.remote.dto.UpdateMarketingRequest
import com.runninggu.app.data.remote.dto.UpdateNicknameRequest
import com.runninggu.app.data.remote.mapper.toSessionProfile

/**
 * 내 정보 변경 창구. (API 명세 §2 · SPEC §4.13 · AP-13)
 *
 * **돌려주는 것이 "바뀐 값" 이 아니라 프로필 전체**다. 서버가 세 엔드포인트 모두에서 같은
 * 응답을 주기로 했기 때문에(§2), 호출부는 무엇이 달라졌는지 따지지 않고 세션을 통째로
 * 갈아끼운다. 그래서 **화면이 스스로 값을 뒤집는 일이 없다** — 서버가 말한 것만 화면에 선다.
 *
 * **인증이 필요하다.** 게스트에게는 계정 화면이 열리지 않으므로 여기서 `401` 을 따로
 * 다루지 않는다 — 세션이 만료된 경우는 `TokenAuthenticator` 가 정리한다(#74).
 */
/**
 * 탈퇴 재인증에 쓸 자격 증명. (§2-2 · D-23 · SPEC 결정-22 개정)
 *
 * **한 계정은 로그인 수단을 하나만 갖는다.** 그래서 "수단 + 비밀번호? + 카카오 토큰?" 세
 * 값을 따로 받으면 셋 다 비어도 컴파일된다 — 화면이 빠뜨리면 서버가 `401 REAUTH_FAILED`
 * 를 주고, 사용자는 **앱이 안 보낸 것을 자기가 틀린 줄 안다**(#198 리뷰).
 *
 * 수단과 값을 한 덩어리로 묶으면 그 실수를 타입이 막는다. 수단이 늘면(구글·네이버 P2)
 * 여기 하나를 더하고, 매퍼의 `when` 이 컴파일 에러로 빠뜨린 자리를 알려 준다.
 */
sealed interface ReauthCredential {
    /** EMAIL 가입자 — 현재 비밀번호. */
    data class Password(val value: String) : ReauthCredential

    /** KAKAO 가입자 — SDK 가 방금 발급한 액세스 토큰. */
    data class Kakao(val accessToken: String) : ReauthCredential
}

interface MemberRepository {

    /**
     * 내 정보 조회. (`GET /me` · 명세 §2)
     *
     * **로그인 응답으로는 프로필이 다 안 채워진다.** `POST /auth/login` · `/auth/kakao` 의
     * `user` 는 닉네임·이메일·가입수단만 있는 **요약**이라 약관이 빠져 있다(§1-5~§1-7).
     * 그래서 재로그인하면 마케팅 동의가 서버는 ON 인데 앱에서 `null`(모름)이 된다.
     * 계정 관리가 이 호출로 채운다(이슈 #287).
     *
     * 시작 시 세션 검증도 같은 엔드포인트를 쓴다(`ApiSessionValidator`). 그쪽은 "이 세션이
     * 살아 있나" 를 묻고 여기는 "지금 값이 뭔가" 를 묻는 것이라, 부르는 자리가 다르다.
     */
    suspend fun me(): SessionProfile

    /** 닉네임 변경. 중복이면 `ApiErrorCode.NICKNAME_DUPLICATED` 가 올라온다. */
    suspend fun updateNickname(nickname: String): SessionProfile

    /** 마케팅 수신 동의 변경. 같은 값을 다시 보내도 멱등 `200` 이다. */
    suspend fun updateMarketing(agreed: Boolean): SessionProfile

    /**
     * 비밀번호 변경 (EMAIL 수단 전용). **돌려주는 것은 프로필이 아니라 새 token pair 다.**
     * (§2-1 · D-28)
     *
     * 위 둘과 달리 세션을 통째로 갈아끼우는 것으로 끝나지 않는다 — 서버가 기존 refresh 를
     * 전부 revoke 했으므로 **호출부가 두 토큰을 원자적으로 저장해야** 한다. 하나만 넣으면
     * 다음 재발급이 실패해 방금 비밀번호를 바꾼 사용자가 로그아웃된다.
     *
     * 현재 비밀번호가 틀리면 `ApiErrorCode.CURRENT_PASSWORD_MISMATCH`, 새 비밀번호 형식
     * 위반이면 `ApiErrorCode.INVALID_PASSWORD` 가 올라온다. **둘을 뭉치면 안 된다** —
     * 전자는 다시 입력할 일이고 후자는 다른 비밀번호를 고를 일이다.
     */
    suspend fun updatePassword(currentPassword: String, newPassword: String): AuthTokens

    /**
     * 탈퇴 재인증. **가입한 수단과 같은 것으로** 한다. (§2-2 · D-23)
     *
     * 돌려주는 것은 **탈퇴에만 쓰는 5분 토큰**이다. [withdraw] 의 헤더로만 넘기고 저장하지
     * 않는다 — 로그에도 남기지 않는다(명세 명시 · AGENTS 8장).
     *
     * 비밀번호·카카오 토큰이 틀리면 `REAUTH_FAILED`, 가입한 것과 다른 수단이면
     * `REAUTH_PROVIDER_MISMATCH` 가 올라온다.
     */
    suspend fun reauth(credential: ReauthCredential): String

    /**
     * 회원 탈퇴. (§2-2 · SPEC §4.13 · D-23)
     *
     * **서버가 지운 뒤에 앱이 정리한다.** 성공하면 서버가 refresh token 전부와 동의·찜·동선·
     * 저장 코스·기록을 지운다. 호출부는 **그 뒤에** 세션과 로컬 캐시를 지운다 — 먼저 로그아웃
     * 하면 탈퇴가 안 된 채 세션만 사라져서, 사용자는 지웠다고 믿는데 계정이 남는다.
     *
     * [reauth] 토큰이 만료(5분)면 `INVALID_REAUTH_TOKEN` 이다. 재인증부터 다시 한다.
     */
    suspend fun withdraw(reauthToken: String)
}

class RemoteMemberRepository(private val api: MeApi) : MemberRepository {

    override suspend fun me(): SessionProfile = apiCall { api.me().toSessionProfile() }

    override suspend fun updateNickname(nickname: String): SessionProfile = apiCall {
        api.updateNickname(UpdateNicknameRequest(nickname)).toSessionProfile()
    }

    override suspend fun updateMarketing(agreed: Boolean): SessionProfile = apiCall {
        api.updateMarketing(UpdateMarketingRequest(agreed)).toSessionProfile()
    }

    override suspend fun updatePassword(currentPassword: String, newPassword: String): AuthTokens =
        apiCall {
            val tokens = api.updatePassword(
                PasswordChangeRequest(currentPassword = currentPassword, newPassword = newPassword),
            )
            AuthTokens(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken)
        }

    /**
     * 수단은 [credential] 이 곧 정한다. 화면이 따로 넘기지 않으므로 **어긋날 수가 없다**
     * — 예전에는 `provider=EMAIL` 에 카카오 토큰을 실어 보내는 조합이 만들어졌다.
     */
    override suspend fun reauth(credential: ReauthCredential): String = apiCall {
        val body = when (credential) {
            is ReauthCredential.Password -> ReauthRequest(
                provider = LoginProvider.EMAIL.name,
                password = credential.value,
            )

            is ReauthCredential.Kakao -> ReauthRequest(
                provider = LoginProvider.KAKAO.name,
                kakaoAccessToken = credential.accessToken,
            )
        }
        api.reauth(body).reauthToken
    }

    override suspend fun withdraw(reauthToken: String) = apiCall { api.withdraw(reauthToken) }
}
