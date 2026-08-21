package com.runninggu.app.ui

import com.runninggu.app.data.remote.ApiException

/**
 * 서버가 준 문구가 있으면 그걸 쓰고, 없으면 기본 문구. (API 명세 §0-3)
 *
 * `detail` 은 개발자용이라 화면에 올리지 않는다.
 *
 * 화면 여럿이 같은 판단을 하므로 `ui/` 에 둔다 — 원래 `ui/course` 에 있었는데 캘린더도
 * 같은 게 필요해졌다. 화면끼리 서로를 import 하게 두면 의존 방향이 엉킨다.
 */
internal fun ApiException.userMessageOrDefault(): String =
    (this as? ApiException.Http)?.userMessage ?: "정보를 불러오지 못했어요."
