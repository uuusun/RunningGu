package com.runninggu.app.ui.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.domain.ItineraryEngine
import com.runninggu.app.domain.ItineraryPlan
import com.runninggu.app.domain.LatLng
import com.runninggu.app.domain.Poi
import com.runninggu.app.domain.PoiCategory
import com.runninggu.app.domain.PoiResult
import com.runninggu.app.domain.PoiSource
import com.runninggu.app.domain.PoiSourceKind
import com.runninggu.app.domain.RaceInfo
import com.runninggu.app.ui.model.RaceSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * S7 동선 결과 ViewModel. (SPEC §4.10 · AP-11)
 *
 * 위저드 상태를 [ItineraryPlan]으로 옮겨 엔진(§5.6)을 돌린다.
 *
 * TODO(AP-14): POI 조회를 서버로 교체한다 — 지금은 [SamplePoiSource]가 채운다.
 *  `GET /api/pois`(API 명세 §4-2)가 붙으면 [PoiSource] 구현만 갈아끼우면 된다.
 * TODO(AP-11): 동선 생성 주체가 서버로 정해지면 `POST /api/itineraries/generate`
 *  (API 명세 §5-1) 호출로 바꾼다. 화면은 [com.runninggu.app.domain.Itinerary]만 보므로
 *  이 클래스 안쪽만 바뀐다.
 */
class ResultViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ResultUiState())
    val uiState: StateFlow<ResultUiState> = _uiState.asStateFlow()

    private var lastPlan: ItineraryPlan? = null

    /** 위저드 상태로 동선을 만든다. 같은 조건이면 다시 만들지 않는다. */
    fun generate(wizard: WizardUiState) {
        val plan = wizard.toPlanOrNull() ?: return
        if (plan == lastPlan && _uiState.value.phase == ResultUiState.Phase.CONTENT) return
        lastPlan = plan
        build(plan)
    }

    /** 오류 상태의 [다시 시도]. 같은 입력으로 재요청한다. (SPEC §4.10) */
    fun retry() {
        lastPlan?.let(::build)
    }

    /** 일자 탭 선택. 지도와 타임라인이 함께 따라간다. (SPEC §4.10) */
    fun onDaySelect(index: Int) {
        _uiState.update { it.copy(activeDayIndex = index) }
    }

    private fun build(plan: ItineraryPlan) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(phase = ResultUiState.Phase.LOADING, errorMessage = null)
            }
            val result = runCatching { ItineraryEngine(SamplePoiSource).build(plan) }
            _uiState.value = result.fold(
                onSuccess = { itinerary ->
                    ResultUiState(
                        // 정상 응답인데 0건이면 오류가 아니라 빈 상태다 (SPEC §4.10).
                        phase = if (itinerary.days.isEmpty()) {
                            ResultUiState.Phase.EMPTY
                        } else {
                            ResultUiState.Phase.CONTENT
                        },
                        itinerary = itinerary,
                    )
                },
                onFailure = {
                    ResultUiState(
                        phase = ResultUiState.Phase.ERROR,
                        errorMessage = "동선을 만들지 못했어요.",
                    )
                },
            )
        }
    }
}

/** 위저드 상태 → 엔진 입력. 일정이 덜 정해졌으면 null. */
private fun WizardUiState.toPlanOrNull(): ItineraryPlan? {
    val race = race ?: return null
    val start = start ?: return null
    val end = end ?: return null
    return ItineraryPlan(
        race = race.toRaceInfo(),
        // TODO(AP-11): S6 숙소 선택이 붙으면 고른 숙소를 넘긴다. null이면 대회장 중심이다(§4.9).
        stay = null,
        event = event,
        themes = themes,
        start = start,
        end = end,
    )
}

/**
 * 화면 모델 → 도메인 모델.
 *
 * 좌표는 아직 [RaceSummary]에 없다. `GET /api/contests/{id}`가 `lat/lng`를 내려주면
 * (API 명세 §3-4) 그 값을 쓴다 — 지금은 POI가 가짜라 좌표가 결과에 영향을 주지 않는다.
 */
private fun RaceSummary.toRaceInfo() = RaceInfo(
    id = id,
    name = name,
    date = date,
    lat = 0.0,
    lng = 0.0,
    venue = venue,
    region = region,
    startTime = startTime,
    officialUrl = officialUrl.orEmpty(),
)

/**
 * 화면 확인용 POI 공급원. (AP-14에서 서버 구현으로 교체)
 *
 * 카테고리마다 이름만 다른 8건을 준다. 엔진의 중복 없는 선택(§5.6-4)이 실제로 동작하는지
 * 화면에서 확인할 수 있을 만큼은 다양하다.
 */
private object SamplePoiSource : PoiSource {

    private val NAMES: Map<PoiCategory, List<String>> = mapOf(
        PoiCategory.FOOD to listOf("한밭식당", "소문난 국밥", "골목 손칼국수", "바다횟집", "정원 한정식", "노포 갈비", "시장 분식", "강변 초밥"),
        PoiCategory.TOUR to listOf("중앙공원 전망대", "역사문화거리", "호수 산책로", "전통시장", "미술관 광장", "강변 데크길", "성곽 둘레", "야경 명소"),
        PoiCategory.CAFE to listOf("로스터리 1호점", "북카페 온", "루프탑 서정", "강변 커피", "골목 디저트", "베이커리 하루", "티하우스 담", "브런치 모모"),
        PoiCategory.WELLNESS to listOf("시티 온천", "스파랜드", "찜질방 휴", "족욕 카페", "힐링 사우나", "온천 호텔 스파", "테라피 센터", "웰니스 라운지"),
        PoiCategory.NATURE to listOf("둘레길 1코스", "수목원", "생태공원", "전망 숲길", "하천 산책로", "억새밭", "해안 트레일", "약수터 길"),
        PoiCategory.HISTORY to listOf("향교", "고택 마을", "박물관", "유적 공원", "옛 성터", "기념관", "서원", "근대 거리"),
        PoiCategory.LODGING to listOf("시티 호텔", "게스트하우스 별", "리조트 뷰", "비즈니스 호텔", "한옥 스테이", "펜션 언덕", "레지던스 온", "모텔 하루"),
    )

    override suspend fun load(category: PoiCategory, center: LatLng, count: Int): PoiResult {
        val places = NAMES[category].orEmpty().take(count).map { name ->
            Poi(name = name, lat = center.lat, lng = center.lng, desc = category.label)
        }
        return PoiResult(PoiSourceKind.SAMPLE, places)
    }
}
