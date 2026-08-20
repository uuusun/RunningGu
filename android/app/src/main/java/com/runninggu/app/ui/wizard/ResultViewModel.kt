package com.runninggu.app.ui.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runninggu.app.data.repository.FakeItineraryRepository
import com.runninggu.app.data.repository.GenerateItineraryRequest
import com.runninggu.app.data.repository.HotelInput
import com.runninggu.app.data.repository.ItineraryRepository
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.BlockType
import com.runninggu.app.domain.ItineraryBlock
import com.runninggu.app.domain.ItineraryDay
import com.runninggu.app.domain.ItineraryEdits
import com.runninggu.app.domain.Poi
import com.runninggu.app.domain.PoiCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * S7 동선 결과 ViewModel. (SPEC §4.10 · AP-11)
 *
 * **동선을 만들지 않는다.** 위저드 선택을 `POST /itineraries/generate` 요청으로 옮겨
 * 서버에 맡기고(결정-41), 받은 응답을 화면 상태로 들고 있는다. 저장 전 USER 블록 편집만
 * 앱 몫이다(§5.7).
 *
 * 서버가 서면 [com.runninggu.app.data.repository.RemoteItineraryRepository] 로 바꾼다 —
 * 화면은 그대로다(AGENTS 4장).
 */
class ResultViewModel(
    private val repository: ItineraryRepository = FakeItineraryRepository,
    private val poiRepository: PoiRepository = FakePoiRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResultUiState())
    val uiState: StateFlow<ResultUiState> = _uiState.asStateFlow()

    /**
     * 스텁 저장소로 데모를 돌릴 때 쓰는 대회 id. **서버 저장소에서는 null 이다.**
     *
     * 샘플·번들 대회에는 canonical id 가 없어서(#66 리뷰) 서버 생성을 부를 수 없다.
     * 데모 화면이 멈추지 않게 스텁 경로에서만 이 값을 쓴다 — 서버로는 절대 안 나간다.
     */
    private val demoContestId: Long? =
        if (repository === FakeItineraryRepository) DEMO_CONTEST_ID else null

    private var lastRequest: GenerateItineraryRequest? = null
    private var lastRegion: String = ""

    /**
     * 후보 시트의 조회 중심. 숙소 > 대회장 순서다. (SPEC §4.10)
     *
     * TODO(AP-14): 숙소를 안 골랐으면 `GET /contests/{id}` 의 대회장 lat/lng 를 쓴다 (API 명세 §3-4).
     *  [RaceSummary] 에 좌표가 없어 지금은 원점이 폴백이다 — S6 StayViewModel.start 와 같은 제약.
     */
    private var sheetCenter: Pair<Double, Double> = 0.0 to 0.0

    /** 위저드 상태로 생성을 요청한다. 같은 조건이면 다시 부르지 않는다. */
    fun generate(wizard: WizardUiState) {
        val request = wizard.toRequestOrNull(demoContestId) ?: run {
            // 화면은 어떤 이유로든 무반응이면 안 된다. 예전에는 위저드 CTA 가 막아 줘서
            // 여기 닿지 않았지만, canonical id 가 없는 대회(번들·오프라인)라는 **새 원인**이
            // 생겼다 — 그대로 두면 스피너가 영원히 돈다 (#66 리뷰)
            _uiState.update { it.copy(phase = ResultUiState.Phase.ERROR, errorMessage = wizard.blockedReason()) }
            return
        }
        // 이전 여정의 숙소 좌표가 남지 않게 매번 다시 정한다.
        sheetCenter = wizard.stay?.let { it.lat to it.lng } ?: (0.0 to 0.0)
        // LOADING 중 LaunchedEffect 재발화(회전·재진입)로 같은 요청이 두 번 나가는 것도 막는다.
        // EMPTY·ERROR 는 재진입 시 다시 시도한다 — 기존 동작 유지.
        if (request == lastRequest &&
            _uiState.value.phase in setOf(ResultUiState.Phase.LOADING, ResultUiState.Phase.CONTENT)
        ) {
            return
        }
        lastRequest = request
        lastRegion = wizard.race?.region.orEmpty()
        send(request)
    }

    /** 오류 상태의 [다시 시도]. 같은 입력으로 재요청한다. (SPEC §4.10) */
    fun retry() {
        lastRequest?.let(::send)
    }

    /** 일자 탭 선택. 지도와 타임라인이 함께 따라간다. (SPEC §4.10) */
    fun onDaySelect(index: Int) {
        _uiState.update { it.copy(activeDayIndex = index) }
    }

    // ── 저장 전 로컬 편집 (SPEC §4.10 · §5.7) ───────────────────
    //
    // 편집은 앱 몫이다(결정-41). 연산은 domain/ItineraryEdits 가 하고 여기서는 결과를
    // 상태에 넣기만 한다 — 대회 블록 거부도 그쪽이 판단한다.

    fun onToggleEdit() {
        _uiState.update { it.copy(isEditing = !it.isEditing) }
    }

    /** 블록 삭제. 대회 블록이면 [ItineraryEdits] 가 거부해 목록이 그대로 온다. */
    fun onRemoveBlock(blockId: String) {
        editActiveDay { days, dayIndex ->
            ItineraryEdits.removeBlock(days, dayIndex, blockId)
        }
    }

    /** 한 칸 위/아래로. 같은 일자 안에서만 움직인다. (SPEC §5.7) */
    fun onMoveBlock(from: Int, to: Int) {
        editActiveDay { days, dayIndex ->
            ItineraryEdits.moveBlock(days, dayIndex, from, to)
        }
    }

    private inline fun editActiveDay(
        transform: (days: List<ItineraryDay>, dayIndex: Int) -> List<ItineraryDay>,
    ) {
        _uiState.update { state ->
            val result = state.result ?: return@update state
            state.copy(result = result.copy(days = transform(result.days, state.activeDayIndex)))
        }
    }

    // ── 후보 시트 (SPEC §4.10) ──────────────────────────────────
    //
    // 교체는 그 블록의 카테고리로 고정 조회하고, 추가는 칩(취향 6종+숙소)으로 바꿔 가며
    // 조회한다. 중심은 숙소 > 대회장이다. 조회는 서버 몫이다 — 앱에 카카오 키가 없다(§9.4).

    /** 편집 행의 [교체]. 그 블록의 카테고리로 후보를 조회한다. */
    fun onReplaceBlock(block: ItineraryBlock) {
        // 대회·회복 블록은 조회 카테고리가 없다. 화면이 버튼을 숨기지만(안전망), 관광지로
        // 잘못 열어 카테고리를 오염시키느니 아무것도 안 하는 쪽이 맞다.
        val category = block.catKey.toPoiCategoryOrNull() ?: return
        openSheet(CandidateSheetState(replaceBlockId = block.id, category = category))
    }

    /** 편집 목록 하단의 [장소 추가]. */
    fun onAddPlace() {
        openSheet(CandidateSheetState())
    }

    /** 추가 모드의 카테고리 칩. 교체 모드에는 칩이 없다. (SPEC §4.10) */
    fun onSheetCategorySelect(category: PoiCategory) {
        val sheet = _uiState.value.sheet ?: return
        if (sheet.isReplace || sheet.category == category) return
        openSheet(sheet.copy(category = category))
    }

    fun onSheetDismiss() {
        sheetRequestId++ // 진행 중이던 조회 응답을 무효화한다
        _uiState.update { it.copy(sheet = null) }
    }

    fun onSheetRetry() {
        _uiState.value.sheet?.let(::openSheet)
    }

    /**
     * 후보 [선택]. (SPEC §4.10 · §5.7)
     *
     * 교체는 장소·설명·카테고리만 바뀌고 블록 id·시간이 유지된다. 추가는 13:00 새 블록으로
     * 맨 끝에 붙는다. 어느 쪽이든 시트를 닫는다.
     */
    fun onCandidateSelect(item: PoiItem) {
        val sheet = _uiState.value.sheet ?: return
        val place = Poi(
            name = item.name,
            lat = item.lat,
            lng = item.lng,
            desc = item.description,
            addr = item.address,
        )
        val catKey = BlockCategory.of(sheet.category)
        editActiveDay { days, dayIndex ->
            val blockId = sheet.replaceBlockId
            if (blockId != null) {
                ItineraryEdits.replacePlace(days, dayIndex, blockId, place, catKey)
            } else {
                ItineraryEdits.addBlock(
                    days, dayIndex,
                    ItineraryBlock(
                        id = "", // addBlock 이 새 id 를 붙인다
                        time = ADDED_BLOCK_TIME,
                        title = item.name,
                        catKey = catKey,
                        place = place,
                        desc = item.description,
                        blockType = BlockType.USER,
                    ),
                )
            }
        }
        onSheetDismiss()
    }

    /** 진행 중 조회를 구분하는 세대 토큰. 시트를 닫거나 새로 열면 이전 응답이 무효가 된다. */
    private var sheetRequestId = 0

    /** 시트 상태를 걸고 후보 8건을 조회한다. */
    private fun openSheet(sheet: CandidateSheetState) {
        val requestId = ++sheetRequestId
        _uiState.update {
            it.copy(sheet = sheet.copy(phase = CandidateSheetState.Phase.LOADING))
        }
        val (lat, lng) = sheetCenter
        viewModelScope.launch {
            val outcome = runCatching { poiRepository.search(sheet.category, lat, lng) }
            _uiState.update { state ->
                // 그 사이 닫혔거나 새 조회가 시작됐으면 낡은 응답을 버린다. 대상 비교가 아니라
                // 세대 비교다 — 같은 대상을 닫았다 다시 열어도 이전 응답이 새 결과를 못 덮는다.
                val current = state.sheet ?: return@update state
                if (requestId != sheetRequestId) return@update state
                state.copy(
                    sheet = outcome.fold(
                        onSuccess = { result ->
                            current.copy(
                                phase = if (result.items.isEmpty()) {
                                    CandidateSheetState.Phase.EMPTY
                                } else {
                                    CandidateSheetState.Phase.CONTENT
                                },
                                items = result.items,
                                source = result.source,
                            )
                        },
                        onFailure = {
                            current.copy(phase = CandidateSheetState.Phase.ERROR)
                        },
                    ),
                )
            }
        }
    }

    private companion object {
        /** 추가 블록의 기본 시간. (SPEC §4.10 "추가=새 블록(13:00) 맨 끝") */
        const val ADDED_BLOCK_TIME = "13:00"
    }

    private fun send(request: GenerateItineraryRequest) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(phase = ResultUiState.Phase.LOADING, errorMessage = null)
            }
            val outcome = runCatching { repository.generate(request) }
            _uiState.value = outcome.fold(
                onSuccess = { result ->
                    ResultUiState(
                        // 200 인데 days=[] 면 오류가 아니라 빈 상태다 (API 명세 §5-1 · SPEC §4.10).
                        phase = if (result.days.isEmpty()) {
                            ResultUiState.Phase.EMPTY
                        } else {
                            ResultUiState.Phase.CONTENT
                        },
                        result = result,
                        event = request.event,
                        region = lastRegion,
                    )
                },
                onFailure = {
                    // 네트워크·timeout·4xx/5xx 는 Error 이며 Empty 로 강등하지 않는다 (API 명세 §5-1).
                    ResultUiState(
                        phase = ResultUiState.Phase.ERROR,
                        event = request.event,
                        region = lastRegion,
                        errorMessage = "동선을 만들지 못했어요.",
                    )
                },
            )
        }
    }
}

/**
 * 생성을 못 부르는 이유. 사용자가 할 일이 다르므로 나눈다. (#66 리뷰)
 *
 * 조건이 덜 정해진 것은 되돌아가면 되고, canonical id 가 없는 것은 사용자가 할 수 있는 게
 * 없다 — 후자는 애초에 CTA 를 막는 게 맞아서 후속으로 @mo-gun 님이 닫기로 했다.
 */
private fun WizardUiState.blockedReason(): String = when {
    race == null || start == null || end == null -> "여행 조건이 덜 정해졌어요. 이전 단계에서 다시 골라 주세요."
    else -> "이 대회는 아직 동선을 만들 수 없어요. 목록을 새로 불러온 뒤 다시 시도해 주세요."
}

/** 스텁 데모 전용 대회 id. 서버 요청에는 실리지 않는다. */
private const val DEMO_CONTEST_ID = 1L

/**
 * 위저드 상태 → 생성 요청. 일정이 덜 정해졌으면 null.
 *
 * @param demoContestId 스텁 저장소로 데모를 돌릴 때만 쓰는 값. 서버 저장소에서는 null 이라
 *  canonical id 가 없는 대회(샘플·번들)로는 생성을 부르지 않는다 (#66 리뷰 · 결정-41).
 */
private fun WizardUiState.toRequestOrNull(demoContestId: Long?): GenerateItineraryRequest? {
    val race = race ?: return null
    val contestId = race.serverId ?: demoContestId ?: return null
    val start = start ?: return null
    val end = end ?: return null
    return GenerateItineraryRequest(
        contestId = contestId,
        startDate = start,
        endDate = end,
        event = event,
        themes = themes,
        // 화면 후보 모델을 그대로 보내지 않는다 — 요청에 필요한 세 값만 옮긴다
        hotel = stay?.let { HotelInput(it.name, it.lat, it.lng) },
    )
}

