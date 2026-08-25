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
interface MemberRepository {

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
    suspend fun reauth(provider: LoginProvider, password: String? = null, kakaoAccessToken: String? = null): String

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

    override suspend fun reauth(
        provider: LoginProvider,
        password: String?,
        kakaoAccessToken: String?,
    ): String = apiCall {
        api.reauth(
            ReauthRequest(
                provider = provider.name,
                password = password,
                kakaoAccessToken = kakaoAccessToken,
            ),
        ).reauthToken
    }

    override suspend fun withdraw(reauthToken: String) = apiCall { api.withdraw(reauthToken) }
}
