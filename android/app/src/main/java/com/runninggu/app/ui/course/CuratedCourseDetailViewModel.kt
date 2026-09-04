package com.runninggu.app.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.data.model.CuratedCourseDetail
import com.runninggu.app.data.repository.CourseRepository
import com.runninggu.app.data.repository.FakeCourseRepository
import com.runninggu.app.ui.userMessageOrDefault
import com.runninggu.app.ui.runCatchingUnlessCancelled
import com.runninggu.app.data.remote.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * S8-D 큐레이션 코스 상세. (#280 · `GET /api/courses/{courseId}`)
 *
 * **저장 코스 상세([CourseDetailViewModel])와 나눈다.** 둘 다 "코스 하나" 를 보여주지만
 * 하는 일이 다르다 — 저장 코스는 지우고, 이것은 읽기만 한다. 한 ViewModel 로 묶으면
 * `delete` 가 큐레이션에서도 보이게 되고 그때 무엇을 지우는지가 애매해진다.
 *
 * **빈 상태가 없다.** id 로 여는 화면이라 없는 id 는 `404` 이고 그건 오류다.
 */
class CuratedCourseDetailViewModel(
    private val repository: CourseRepository = FakeCourseRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CuratedCourseDetailUiState())
    val uiState: StateFlow<CuratedCourseDetailUiState> = _uiState.asStateFlow()

    private var loadedId: String? = null

    /**
     * 상세를 조회한다. **같은 id 로 다시 들어오면 다시 부르지 않는다** — 회전·재진입으로
     * `LaunchedEffect` 가 다시 도는데, 그때마다 부르면 네트워크가 두 번 나간다.
     */
    fun load(courseId: String, force: Boolean = false) {
        if (!force && loadedId == courseId) return
        loadedId = courseId
        _uiState.update { it.copy(phase = CuratedCourseDetailUiState.Phase.LOADING, errorMessage = null) }
        viewModelScope.launch {
            runCatchingUnlessCancelled { repository.detail(courseId) }
                .onSuccess { detail -> _uiState.update { it.copy(phase = CuratedCourseDetailUiState.Phase.CONTENT, detail = detail) } }
                .onFailure { cause ->
                    // 실패하면 내용을 비운다 — 앞 코스가 남아 있으면 어느 코스 오류인지 모른다
                    _uiState.update {
                        it.copy(
                            phase = CuratedCourseDetailUiState.Phase.ERROR,
                            detail = null,
                            errorMessage = (cause as? ApiException)?.userMessageOrDefault(),
                        )
                    }
                }
        }
    }

    /** [다시 시도]. 같은 id 를 **강제로** 다시 부른다 — 안 그러면 위 가드에 막힌다. */
    fun retry() {
        loadedId?.let { load(it, force = true) }
    }
}

/**
 * S8-D 큐레이션 코스 상세의 UI 계약. (#280)
 *
 * 저장 코스 상세와 달리 삭제가 없어서 `pendingDelete`·`deleting`·`deleted` 가 없다.
 */
data class CuratedCourseDetailUiState(
    val phase: Phase = Phase.LOADING,
    val detail: CuratedCourseDetail? = null,
    val errorMessage: String? = null,
) {
    enum class Phase { LOADING, CONTENT, ERROR }
}
