package com.runninggu.app.data.repository

import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.remote.MeApi
import com.runninggu.app.data.remote.apiCall
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
}

class RemoteMemberRepository(private val api: MeApi) : MemberRepository {

    override suspend fun updateNickname(nickname: String): SessionProfile = apiCall {
        api.updateNickname(UpdateNicknameRequest(nickname)).toSessionProfile()
    }

    override suspend fun updateMarketing(agreed: Boolean): SessionProfile = apiCall {
        api.updateMarketing(UpdateMarketingRequest(agreed)).toSessionProfile()
    }
}
