package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.local.LoginProvider
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.remote.dto.MeDto

/**
 * `GET /api/me` 응답을 세션 프로필로. (API 명세 §2)
 *
 * **모르는 `loginProvider` 는 예외로 올린다.** 기본값으로 바꾸면 카카오 가입자가 이메일
 * 가입자로 보이고, 계정 화면이 없는 "비밀번호 변경" 을 띄운다. 계약이 바뀐 것이므로
 * 조용히 넘기지 않는다.
 */
fun MeDto.toSessionProfile(): SessionProfile = SessionProfile(
    nickname = nickname,
    // null 이면 화면이 이메일 행을 숨긴다 — 없는 것과 못 받은 것을 구분하지 않는다(#59)
    email = email,
    loginProvider = LoginProvider.entries.firstOrNull { it.name == loginProvider }
        ?: throw IllegalArgumentException("모르는 loginProvider: $loginProvider"),
    marketingAgreed = agreements.marketing,
)
