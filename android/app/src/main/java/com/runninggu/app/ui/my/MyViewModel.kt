package com.runninggu.app.ui.my

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.model.SavedCourse
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.SavedCourseRepository
import com.runninggu.app.ui.favorite.FavoriteStore
import com.runninggu.app.ui.favorite.FavoriteToggleResult
import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.ui.sample.SampleData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** S10 세그먼트 3개. (SPEC §4.13) */
enum class MySegment(val label: String) {
    ITINERARY("동선"),
    COURSE("러닝코스"),
    FAVORITE("찜한 대회"),
}

/**
 * 저장한 동선 카드 한 장. (SPEC §4.13 [동선] · API 명세 §5-3 목록)
 *
 * TODO(AP-14): `GET /api/itineraries` 응답 DTO 매핑으로 교체한다. 회복 배지 등 카드
 *  표시 필드는 TBD-DB-04(저장 snapshot 정책, 이슈 #55) 결정에 따라 달라질 수 있다.
 */
data class SavedItinerary(
    val id: String,
    /** "{지역} {당일치기|n박 n일}" */
    val title: String,
    val raceName: String,
    val event: String,
    /** 회복 배지 라벨. noHard 종목이 아니면 null. */
    val recoveryLabel: String?,
    /** "MM.DD~MM.DD" */
    val period: String,
    val placeCount: Int,
)

/**
 * 저장한 러닝코스는 `data/model` 의 [SavedCourse] 를 그대로 쓴다. P0는 saved만이다
 * (SPEC §4.13 · 결정 D-25).
 *
 * 화면 전용 모델을 따로 두지 않는 이유는, 카드가 쓰는 값이 서버 응답의 부분집합이고
 * **상세로 넘길 canonical id(`Long`)** 가 그대로 필요하기 때문이다. 옮겨 담으면 id 를
 * 문자열로 바꿨다 되돌리는 일만 생긴다.
 */


/**
 * S10 마이의 UI 계약. (SPEC §4.13 · §3-5)
 *
 * 마이 진입 자체가 로그인 필요다(결정-4) — [profile]이 null(게스트)이면 화면은
 * 로그인 유도만 그린다.
 */
data class MyUiState(
    val profile: SessionProfile? = null,
    val segment: MySegment = MySegment.ITINERARY,
    val itineraries: List<SavedItinerary> = emptyList(),
    val courses: List<SavedCourse> = emptyList(),
    val favoriteRaces: List<RaceSummary> = emptyList(),
)

/**
 * S10 마이. (SPEC §4.13 · AP-13)
 *
 * 저장소 SSOT는 서버다 — 동선·코스 목록은 TODO(AP-14) Retrofit + Room 읽기 캐시로
 * 교체한다. 찜은 [FavoriteStore]를 그대로 읽어 S2 카드·S3 상세와 같은 값을 보인다.
 */
class MyViewModel(
    private val savedCourseRepository: SavedCourseRepository = ServiceLocator.savedCourseRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MyUiState())
    val uiState: StateFlow<MyUiState> = _uiState.asStateFlow()

    /** 찜 해제 실패 등 스낵바. (SPEC §3-4) */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            SessionStore.session.collect { profile ->
                _uiState.update {
                    it.copy(
                        profile = profile,
                        // 데모 목록은 로그인 상태에서만 보인다 — 게스트 화면 검증을 막지 않게.
                        itineraries = if (profile != null) SAMPLE_ITINERARIES else emptyList(),
                        courses = if (profile == null) emptyList() else it.courses,
                    )
                }
                // 서버 SSOT 를 다시 읽는다. 게스트면 캐시를 비운다 (SPEC §4.13 · AP-21).
                FavoriteStore.refresh()
                if (profile != null) loadCourses()
            }
        }
        viewModelScope.launch {
            FavoriteStore.favoriteIds.collect { ids ->
                _uiState.update { state ->
                    // 공개 목록이 아니라 전체를 본다 — 찜 목록은 비활성·지난 대회도
                    // 유지하는 게 계약이다 (API 명세 §7-C · 결정-46).
                    state.copy(favoriteRaces = SampleData.allRaces.filter { it.id in ids })
                }
            }
        }
    }

    fun onSegmentSelect(segment: MySegment) {
        _uiState.update { it.copy(segment = segment) }
    }

    /**
     * 저장 코스 목록. (API 명세 §7-A · SPEC §4.13)
     *
     * 실패는 조용히 둔다 — 세 세그먼트 중 하나라서 화면 전체를 오류로 덮으면 동선·찜까지
     * 못 본다(§3-5 영역 단위 부분 실패). 다시 들어오면 재조회된다.
     *
     * 상세에서 삭제하고 돌아왔을 때도 이걸 다시 부른다 — 목록에서 빠진 것을 보여야 한다.
     */
    fun loadCourses() {
        viewModelScope.launch {
            val courses = try {
                savedCourseRepository.list().courses
            } catch (e: ApiException) {
                // 서버 문구 대신 이 자리에서 뭘 못 불렀는지 말한다 — 마이는 세그먼트가
                // 셋이라 "정보를 불러오지 못했어요" 로는 어느 탭인지 알 수 없다.
                _message.value = "저장한 코스를 불러오지 못했어요."
                return@launch
            }
            _uiState.update { it.copy(courses = courses) }
        }
    }

    /** [동선] 카드의 [삭제]. (SPEC §4.13 🔧정책) */
    fun onDeleteItinerary(id: String) {
        _uiState.update { state ->
            state.copy(itineraries = state.itineraries.filterNot { it.id == id })
        }
    }

    /**
     * 찜 해제 — 하트 재탭. 목록에서 바로 빠진다 (SPEC §4.13 · AP-21).
     *
     * 마이는 로그인 상태에서만 열리므로 `LoginRequired` 는 오지 않는다. 서버 실패만 알린다.
     */
    fun onFavoriteToggle(raceId: String) {
        viewModelScope.launch {
            if (FavoriteStore.toggle(raceId) == FavoriteToggleResult.Failed) {
                _message.value = "찜을 해제하지 못했어요. 잠시 후 다시 시도해 주세요."
            }
        }
    }

    fun onMessageShown() {
        _message.value = null
    }

    private companion object {
        /** TODO(AP-14): `GET /api/itineraries`·`GET /api/me/courses` 로 교체하는 데모 데이터. */
        val SAMPLE_ITINERARIES = listOf(
            SavedItinerary(
                id = "it_1",
                title = "서울 2박 3일",
                raceName = "서울 한강 러닝 페스티벌",
                event = "10K",
                recoveryLabel = null,
                period = "09.05~09.07",
                placeCount = 10,
            ),
            SavedItinerary(
                id = "it_2",
                title = "세종 1박 2일",
                raceName = "세종 호수공원 마라톤",
                event = "하프",
                recoveryLabel = "회복 모드",
                period = "09.08~09.09",
                placeCount = 6,
            ),
        )
        // 저장 코스는 SavedCourseRepository 가 준다 — 데모 목록을 여기 두지 않는다.
    }
}
