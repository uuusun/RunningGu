package com.runninggu.app.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.SavedCourseRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 저장 코스 상세. (screen-api-matrix S8-D `saved` · §7-A)
 *
 * `near` · `ran` 변형은 여기서 다루지 않는다 — `near` 는 S8 목록이 snapshot 을 넘기는
 * 흐름(AP-12)이고 `ran` 은 P1 이다(결정-14).
 */
class CourseDetailViewModel(
    private val savedCourseId: Long,
    private val repository: SavedCourseRepository = ServiceLocator.savedCourseRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CourseDetailUiState())
    val uiState: StateFlow<CourseDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(phase = CourseDetailUiState.Phase.LOADING, errorMessage = null)
            }
            try {
                val detail = repository.detail(savedCourseId)
                _uiState.update {
                    it.copy(phase = CourseDetailUiState.Phase.CONTENT, detail = detail)
                }
            } catch (e: ApiException) {
                _uiState.update {
                    it.copy(
                        phase = CourseDetailUiState.Phase.ERROR,
                        errorMessage = e.userMessageOrDefault(),
                    )
                }
            }
        }
    }

    fun onDeleteRequest() {
        _uiState.update { it.copy(pendingDelete = true) }
    }

    fun onDeleteCancel() {
        _uiState.update { it.copy(pendingDelete = false) }
    }

    /**
     * 삭제 확정. (§4.13 `[삭제]` 🔧정책)
     *
     * 낙관적으로 목록에서 먼저 빼지 않는다 — 여기서 실패하면 사용자는 이미 목록으로
     * 돌아간 뒤라, 되돌아온 항목을 보고 무슨 일이 있었는지 알 수 없다. 서버가 지웠다고
     * 답한 뒤에 화면을 닫는다.
     */
    fun onDeleteConfirm() {
        if (_uiState.value.deleting) return
        viewModelScope.launch {
            _uiState.update { it.copy(deleting = true) }
            try {
                repository.delete(savedCourseId)
                _uiState.update { it.copy(deleting = false, pendingDelete = false, deleted = true) }
            } catch (e: ApiException) {
                // 모달은 닫는다. 오류를 모달 위에 겹쳐 놓으면 어느 쪽을 눌러야 할지 모른다
                _uiState.update {
                    it.copy(
                        deleting = false,
                        pendingDelete = false,
                        errorMessage = e.userMessageOrDefault(),
                    )
                }
            }
        }
    }

    /** 오류 안내를 본 뒤. 상세 내용은 그대로 두고 문구만 지운다. */
    fun onErrorShown() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        fun factory(
            savedCourseId: Long,
            repository: SavedCourseRepository = ServiceLocator.savedCourseRepository,
        ) = viewModelFactory {
            initializer { CourseDetailViewModel(savedCourseId, repository) }
        }
    }
}
