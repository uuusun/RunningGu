package com.runninggu.app.ui.course

import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.CourseSource
import com.runninggu.app.data.model.CourseSummary
import com.runninggu.app.data.model.CourseTargetKm
import com.runninggu.app.data.model.NearbyItem

/**
 * S8 러닝코스의 UI 계약. (SPEC §4.11 · API 명세 §6)
 *
 * 탭 두 개가 서로 독립이다 — 내 주변이 실패해도 지역별은 보여야 한다.
 *
 * TODO(#49): 홈·캘린더에서 논의 중인 공용 `SectionState` 가 정해지면 여기도 옮긴다.
 *  지금은 S8 만의 상태로 두어 그 논의를 앞질러 정하지 않는다.
 */
data class CourseUiState(
    val tab: Tab = Tab.NEARBY,
    val origin: OriginState = OriginState.Undecided,
    /** 목표 거리(km). 범위·단위는 [CourseTargetKm] 이 유일한 출처다. (SPEC §4.11-2) */
    val targetKm: Double = CourseTargetKm.DEFAULT,
    /** 출발지 검색 입력·결과. (SPEC §4.11-1 ②) */
    val originSearch: OriginSearchState = OriginSearchState(),
    val nearby: NearbyState = NearbyState.Idle,
    val regions: RegionsState = RegionsState.Loading,
    /** 지역 칩 선택. null 이면 전국이다. 재탭하면 해제된다. (§4.11-b) */
    val selectedRegion: String? = null,
    val regionCourses: RegionCoursesState = RegionCoursesState.Loading,
    /** 목록에서 고른 항목. 지도 폴리라인이 이걸 따라간다. (§4.11-4) */
    val selectedRouteId: String? = null,
) {
    enum class Tab(val label: String) {
        NEARBY("내 주변"),
        BY_REGION("지역별"),
    }
}

/**
 * 출발지. **권한을 거부해도 화면이 동작해야 한다**(NFR-15) — 검색과 프리셋이 그래서 있다.
 */
sealed interface OriginState {
    /** 아직 못 정했다. "출발지를 정해주세요." */
    data object Undecided : OriginState

    /** GPS 조회 중. 6초 타임아웃. "위치를 확인하는 중…" */
    data object Locating : OriginState

    data class Fixed(
        val name: String,
        val lat: Double,
        val lng: Double,
        val from: Source,
    ) : OriginState {
        enum class Source { GPS, SEARCH, PRESET, ITINERARY }
    }
}

/**
 * 출발지 검색. (SPEC §4.11-1 ② · API 명세 §4-4)
 *
 * 서버가 **카카오 키워드 첫 결과 하나**만 주므로 후보 목록이 없다 — 찾으면 바로 출발지가 된다.
 * 그래서 이 상태에는 결과가 없고 진행 상황과 실패 문구만 있다.
 */
data class OriginSearchState(
    val query: String = "",
    val searching: Boolean = false,
    /** 실패 문구. 없으면 null. `404 NO_RESULT` 와 그 밖의 실패를 다르게 적는다. */
    val message: String? = null,
) {
    /** 공백만 넣고 누르면 부르지 않는다. */
    val canSubmit: Boolean get() = query.isNotBlank() && !searching
}

/**
 * 내 주변 조회 결과. (§4.11-7)
 *
 * **정상 0건과 오류를 구분한다.** 품질 상한을 통과한 경로가 없는 것은 정상 0건이지 실패가
 * 아니다 — 그걸 Error 로 그리면 사용자가 앱이 고장 났다고 생각한다.
 */
sealed interface NearbyState {
    /** 출발지가 없어 아직 조회하지 않았다. */
    data object Idle : NearbyState

    data object Loading : NearbyState

    data class Content(
        /** 서버가 `distanceM` 오름차순으로 섞어 준 순서 그대로. 앱은 재정렬하지 않는다. */
        val items: List<NearbyItem>,
        /** 출처 문구. 순서·문구를 바꾸지 않고 그대로 표시한다(공공누리·ODbL). */
        val attributions: List<String>,
        /** 일부 원천이 실패했다. 목록은 보여주되 비차단 안내를 함께 낸다. */
        val degradedSources: List<CourseSource> = emptyList(),
    ) : NearbyState {
        /** 따라갈 경로가 하나도 없다 — 버튼 아래 안내를 붙인다. (§4.11-6) */
        val hasNoRoute: Boolean get() = items.none { it is NearbyItem.Route }

        /**
         * 부분 실패 안내 문구. 없으면 null. (§4.11-7)
         *
         * OSM 만 실패했는데 장소가 있으면 문구가 따로 정해져 있다.
         */
        val degradedMessage: String?
            get() = when {
                degradedSources.isEmpty() -> null
                degradedSources == listOf(CourseSource.OSM) && items.isNotEmpty() ->
                    "자동 경로를 만들지 못해 주변 장소를 보여드려요."
                else -> "일부 정보를 불러오지 못했어요. 보이는 것만 표시합니다."
            }
    }

    /** 정상 조회했는데 0건. "이 근처엔 걸을 곳을 못 찾았어요." */
    data object Empty : NearbyState

    /** 원천 실패로 표시할 게 하나도 없다. 재시도를 제공한다. */
    data class Error(val message: String) : NearbyState
}

/** 지역 칩. 코스 수 내림차순은 서버가 정한다. (§4.11-b · §6-3) */
sealed interface RegionsState {
    data object Loading : RegionsState
    data class Content(val regions: List<CourseRegion>) : RegionsState

    /** 칩을 못 불러와도 목록은 전국 기준으로 보여줄 수 있다. */
    data class Error(val message: String) : RegionsState
}

/** 지역별 코스 목록. (§4.11-b · §6-2) */
sealed interface RegionCoursesState {
    data object Loading : RegionCoursesState
    data class Content(
        /** 지금까지 받아온 것을 **이어 붙인** 목록. 다음 장을 받으면 뒤에 붙는다. */
        val courses: List<CourseSummary>,
        val hasNext: Boolean,
        /**
         * 목록 하단 출처 한 줄. (SPEC §4.11-b · 결정-44)
         *
         * 순서·문구를 바꾸지 않고 그대로 표시한다 — 공공누리·ODbL 출처표시 의무다.
         */
        val attributions: List<String> = emptyList(),
        /**
         * 조건에 맞는 전체 코스 수. 칼럼 "{지역} 코스 N" 에 쓴다 (§4.11-b).
         *
         * `courses.size` 가 아니다 — 한 번에 20건씩 받으므로 그렇게 세면 틀어진다.
         */
        val totalElements: Long,
        /** [더 보기] 를 눌러 다음 장을 받는 중. 목록은 그대로 두고 버튼만 바뀐다. */
        val loadingMore: Boolean = false,
        /**
         * 다음 장을 못 받았다. **이미 받은 목록은 지우지 않는다** — 20건이라도 보이는 게
         * 빈 화면보다 낫다(§4.11-7 의 부분 실패와 같은 취지).
         */
        val moreMessage: String? = null,
    ) : RegionCoursesState {
        /** 더 받을 게 남았고 지금 받는 중이 아니다. */
        val canLoadMore: Boolean get() = hasNext && !loadingMore
    }

    /** "이 지역엔 코스가 없어요." */
    data object Empty : RegionCoursesState
    data class Error(val message: String) : RegionCoursesState
}

/** 출발지 프리셋 5개. 위치 권한을 거부해도 쓸 수 있다. (SPEC §4.11-1) */
val ORIGIN_PRESETS: List<OriginState.Fixed> = listOf(
    OriginState.Fixed("부산 해운대", 35.1587, 129.1604, OriginState.Fixed.Source.PRESET),
    OriginState.Fixed("여수", 34.7604, 127.6622, OriginState.Fixed.Source.PRESET),
    OriginState.Fixed("강릉", 37.7519, 128.8761, OriginState.Fixed.Source.PRESET),
    OriginState.Fixed("인천 강화", 37.7469, 126.4878, OriginState.Fixed.Source.PRESET),
    OriginState.Fixed("서울시청", 37.5665, 126.9780, OriginState.Fixed.Source.PRESET),
)
