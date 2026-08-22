package com.runninggu.app.ui.course

import com.runninggu.app.data.model.SavedCourseDetail

/**
 * S8-D 코스 상세의 UI 계약. (screen-api-matrix S8-D · §7-A)
 *
 * **id 로 여는 화면이라 빈 상태가 없다.** 없는 id 면 404 이고 그건 오류다 — 목록이 비는
 * 것과 다르다. 그래서 세 상태만 둔다.
 *
 * 삭제 확인은 [pendingDelete] 로 화면이 아니라 상태에 둔다. 모달을 띄운 채 회전하면
 * 화면 지역 상태는 사라지는데, 사용자는 "삭제하시겠어요?" 에 답하던 중이었다.
 */
data class CourseDetailUiState(
    val phase: Phase = Phase.LOADING,
    val detail: SavedCourseDetail? = null,
    val errorMessage: String? = null,
    /** 삭제 확인 모달이 떠 있는가. */
    val pendingDelete: Boolean = false,
    /** 삭제 중. 확인 버튼을 두 번 누르지 못하게 한다. */
    val deleting: Boolean = false,
    /** 삭제가 끝났다. 화면이 목록으로 돌아간다. */
    val deleted: Boolean = false,
) {
    enum class Phase { LOADING, CONTENT, ERROR }
}
