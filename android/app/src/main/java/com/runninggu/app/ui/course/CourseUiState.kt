package com.runninggu.app.ui.course

import com.runninggu.app.data.model.CourseRegion
import com.runninggu.app.data.model.CourseSource
import com.runninggu.app.data.model.CourseSummary
import com.runninggu.app.data.model.CourseTargetKm
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.ui.map.MapMarker

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
    /**
     * 목록에서 고른 항목. 지도 폴리라인과 카드 강조가 이걸 따라간다. (§4.11-4)
     *
     * **경로 id 하나로 들지 않는다.** 그러면 "아직 아무것도 안 골랐다" 와 "걷기 스팟을
     * 골랐다" 가 둘 다 null 이 되어 구분되지 않는다. 그 탓에 스팟을 탭하면 관계없는 첫
     * 코스 선이 그려지고, 조회 직후에는 `null == null` 이 참이라 **모든 스팟 카드가
     * 강조**됐다(#142 리뷰).
     *
     * 같은 항목이 목록에 두 번 오면 둘 다 강조된다. 서버가 거리순으로 섞어 주되 중복은
     * 주지 않으므로 실제로는 생기지 않는다(§4.11-5).
     */
    val selectedItem: NearbyItem? = null,
    /** [저장] 버튼. (API 명세 §7-A · SPEC §4.11-6) */
    val save: SaveCourseState = SaveCourseState.Idle,
    /**
     * [내 위치] 가 실패한 이유. (SPEC §4.11-1 ① · NFR-15)
     *
     * 화면 전체를 막지 않는다 — 출발지는 검색·프리셋으로도 정할 수 있다. **출발지가 실제로
     * 정해지면 사라진다**(GPS 성공·검색 성공·프리셋 선택) — 안내대로 골랐는데 "아래에서
     * 골라 주세요" 가 남아 있으면 뭔가 덜 된 것처럼 읽힌다(#92 리뷰).
     */
    val locationMessage: String? = null,
) {
    /**
     * 지도에 그릴 경로. (SPEC §4.11-4 · §3-8)
     *
     * **고르기 전에는 첫 코스를 그린다.** 조회 직후 [selectedItem] 은 null 인데
     * (`CourseViewModel` 이 새 조회마다 지운다) 그때 지도를 비워 두면 목록을 한 번
     * 탭하기 전까지 빈 회색 판이 놓인다. 서버가 거리순으로 준 첫 코스가 기본이다.
     *
     * **걷기 스팟을 골랐으면 그릴 것이 없다.** 첫 코스로 되돌리면 방금 탭한 것과 아무
     * 상관 없는 선이 지도에 남는다. §4.11-4 의 번호 핀이 붙기 전까지는 비어 있는 것이
     * 맞다.
     */
    val mappedRoute: NearbyItem.Route?
        get() {
            val routes = (nearby as? NearbyState.Content)
                ?.items
                ?.filterIsInstance<NearbyItem.Route>()
                ?: return null
            return when (val picked = selectedItem) {
                is NearbyItem.Place -> null
                is NearbyItem.Route ->
                    routes.firstOrNull { it.routeId == picked.routeId } ?: routes.firstOrNull()
                null -> routes.firstOrNull()
            }
        }

    /**
     * 지도에 세울 번호 핀. (SPEC §4.11-4)
     *
     * 명세가 지도를 두 갈래로 가른다.
     *
     * > 선택 항목이 **경로면 왕복 폴리라인**(경로 bounds), **그 외 번호 핀**(잇지 않음,
     * > 리스트 번호 일치)
     *
     * **"그릴 경로가 없을 때" 를 기준으로 삼는다.** 그러면 세 경우가 한 규칙으로 덮인다 —
     * 걷기 스팟을 골랐을 때, 코스가 아예 0건일 때, 그리고 조회 직후 기본 경로도 없을 때다.
     * 서울 반경 8km 는 코스 0건에 스팟만 나오는 것이 기본이라(§4.11 📌 · AGENTS 6장)
     * 마지막 경우를 빼면 **수도권에서 지도가 늘 비어 있다.**
     *
     * **번호는 목록 순번 그대로다.** `index + 1` 은 화면이 목록에 붙이는 번호와 같은
     * 값이다(#158 `itemsIndexed`). 스팟만 따로 1·2·3 으로 다시 매기면 그 순간 명세가
     * 요구하는 "리스트 번호 일치" 가 깨진다.
     *
     * 그래서 **핀 번호는 중간이 빈다.** 목록이 `1 경로 · 2 스팟 · 3 스팟 · 4 경로 ·
     * 5 스팟` 이면 지도에는 `2 · 3 · 5` 만 선다. 비는 것이 계약대로 맞는 동작이다.
     */
    val mapPins: List<MapMarker>
        get() {
            // 그릴 경로가 있으면 그쪽이 화면을 갖는다 — 선과 핀을 같이 그리지 않는다
            if (mappedRoute != null) return emptyList()
            val items = (nearby as? NearbyState.Content)?.items ?: return emptyList()
            return items.mapIndexedNotNull { index, item ->
                (item as? NearbyItem.Place)?.let {
                    MapMarker(id = pinId(index), order = index + 1, lat = it.lat, lng = it.lng)
                }
            }
        }

    /**
     * 지금 보고 있는 핀. 카메라가 여기로 따라간다. (SPEC §3-8)
     *
     * 고른 것이 스팟일 때만 있다 — 경로를 골랐으면 핀 자체가 없다.
     */
    val activePinId: String?
        get() {
            if (selectedItem !is NearbyItem.Place) return null
            val items = (nearby as? NearbyState.Content)?.items ?: return null
            val index = items.indexOf(selectedItem)
            return if (index >= 0) pinId(index) else null
        }

    /**
     * 핀 식별자. **목록 위치로 만든다.**
     *
     * `NearbyItem.Place` 에는 서버가 준 id 가 없다(§6-1 은 `ROUTE` 에만 `routeId` 를
     * 준다). 이름·좌표를 이어 붙이면 같은 장소가 두 번 왔을 때 id 가 겹치므로, 목록
     * 안에서 반드시 유일한 값인 위치를 쓴다. 목록이 바뀌면 새로 계산되는 값이라
     * 저장하거나 서버로 보내지 않는다.
     */
    private fun pinId(index: Int): String = "nearby-$index"

    enum class Tab(val label: String) {
        NEARBY("내 주변"),
        BY_REGION("지역별"),
    }

    /**
     * [저장] 이 담는 경로. **지도가 그리는 것과 같다.** (§4.11-4 · §7-A)
     *
     * 고른 것이 있으면 그것, 없으면 첫 코스다 — [mappedRoute] 그대로다.
     *
     * **"지도에 보이는 것을 저장한다" 가 규칙이다.** 고른 것만 저장 대상으로 삼으면,
     * 조회 직후 지도에는 첫 코스가 그려져 있는데 [저장] 은 회색인 화면이 된다(#166 리뷰).
     *
     * 무엇이 저장되는지는 **목록 강조가 말해 준다.** `CourseViewModel` 이 조회 직후 첫
     * 경로를 [selectedItem] 으로 고른 상태로 둔다 — 예전에는 그걸 비워 두고 "첫 코스는
     * 목록에서도 1번" 이라고 적어 뒀는데, **서버가 경로와 장소를 거리순으로 섞어 주므로
     * 그 전제가 틀렸다**(§6-1 · #190 리뷰). 스팟이 앞에 오면 지도의 경로는 목록 4번일
     * 수도 있고, 그때 강조가 없으면 어느 코스를 저장하는지 알 수 없다.
     *
     * 걷기 스팟([NearbyItem.Place])을 고르면 [mappedRoute] 가 null 이라 여기도 null 이다 —
     * 지도가 비어 있는데 저장이 눌리면 무엇을 저장하는지 알 수 없다.
     *
     * 저장 요청 본문에는 서버가 준 `pathPolyline` 과 원천 메타가 **그대로** 들어가야 한다
     * (이슈 #62). 화면이 값을 재조립하면 fingerprint 가 흔들려 같은 코스가 중복 저장된다.
     */
    val selectedRoute: NearbyItem.Route?
        get() = mappedRoute

    /** [저장] 을 누를 수 있는가. 고른 경로가 있고 보내는 중이 아니어야 한다. */
    val canSave: Boolean get() = selectedRoute != null && save !is SaveCourseState.Saving
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

/**
 * [저장] 버튼 상태. (API 명세 §7-A · `POST /me/courses`)
 *
 * ## 결과를 문구 하나로 모은다
 *
 * 저장은 **한 번 누르고 끝나는 동작**이라 화면에 남는 것이 안내 한 줄뿐이다. 성공·중복·
 * 실패마다 상태를 따로 만들면 화면이 `when` 을 세 갈래로 벌리는데, 정작 그리는 것은 셋 다
 * 같은 자리의 같은 문구다. 대신 [Done.failed] 로 **색만** 가른다.
 *
 * 목록·조회의 네 상태(§3-5)와 다른 모양인 것은 성격이 달라서다 — 저장에는 "빈 결과" 가 없다.
 */
sealed interface SaveCourseState {

    /** 아직 안 눌렀거나, 고른 항목이 바뀌어 이전 결과를 지웠다. */
    data object Idle : SaveCourseState

    /** 보내는 중. 버튼을 막아 연타로 같은 코스가 두 번 나가지 않게 한다. */
    data object Saving : SaveCourseState

    /**
     * 게스트가 눌렀다 — 로그인 유도 **모달**을 띄운다.
     * (`docs/screen-api-matrix.md` S8 "코스 저장 … 게스트 modal")
     *
     * 아래 [Done] 의 문구 한 줄로 처리하지 않는 이유는, 로그인은 **화면을 옮겨야 끝나는
     * 일**이라서다. 안내만 남기면 어디로 가야 하는지 모른 채 버튼만 다시 누르게 된다.
     *
     * 로그인하고 돌아와도 **저장이 저절로 일어나지 않는다**(D-27) — 누른 적 없는 저장이
     * 실행되면 사용자가 놀란다. 돌아온 자리에서 다시 누르면 된다.
     */
    data object NeedsLogin : SaveCourseState

    /**
     * 끝났다. [message] 를 버튼 아래 한 줄로 그린다.
     *
     * @param failed 실패·로그인 필요처럼 **사용자가 뭔가 해야 하는** 결과인가. 화면이 색을
     *  가르는 데만 쓴다 — "이미 저장한 코스예요" 는 실패가 아니라 false 다.
     */
    data class Done(val message: String, val failed: Boolean = false) : SaveCourseState
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
