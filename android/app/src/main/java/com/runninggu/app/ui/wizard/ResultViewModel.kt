package com.runninggu.app.ui.wizard

import com.runninggu.app.ui.runCatchingUnlessCancelled
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.runninggu.app.data.model.PoiItem
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.apiErrorCode
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.data.repository.PoiRepository
import com.runninggu.app.ui.course.saveMessage

/**
 * S7 동선 결과 ViewModel. (SPEC §4.10 · AP-11)
 *
 * **동선을 만들지 않는다.** 위저드 선택을 `POST /itineraries/generate` 요청으로 옮겨
 * 서버에 맡기고(결정-41), 받은 응답을 화면 상태로 들고 있는다. 저장 전 USER 블록 편집만
 * 앱 몫이다(§5.7).
 *
 * **저장소는 서버다** — 생성(`POST /api/itineraries/generate`)과 저장
 * (`POST /api/itineraries`) 둘 다 서 있다(AP-07). 예전에는
 * [com.runninggu.app.data.repository.FakeItineraryRepository] 를 들고 있었는데,
 * 가짜에는 `save()` 가 없어 저장 CTA 를 붙일 수 없었다 — 위저드가 서버 대회를 싣게
 * 되면서(#140 · `contestPhase`) 옮길 조건이 갖춰졌다.
 */
class ResultViewModel(
    /**
     * 생성·저장 둘 다 서버가 한다. (SPEC 결정-41 · API 명세 §5-1 · §5-2)
     *
     * 테스트는 [FakeItineraryRepository] 나 자체 스텁을 넣는다. 가짜를 넣으면
     * [demoContestId] 가 살아나 `serverId` 없는 샘플 대회로도 화면이 돈다.
     */
    private val repository: ItineraryRepository = ServiceLocator.itineraryRepository,
    /** 교체·추가 시트의 후보. 이쪽은 서버에 `GET /api/pois` 가 있어 옮겼다. */
    private val poiRepository: PoiRepository = ServiceLocator.poiRepository,
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

    /** 저장 요청. 내용을 고치면 이전 결과가 [SaveItineraryState.Idle] 로 지워지므로 함께 끊는다. */
    private var saveJob: Job? = null
    private var lastRegion: String = ""

    /**
     * 후보 시트의 조회 중심. **숙소 > 대회장** 순서다. (SPEC §4.10)
     *
     * 숙소는 선택 사항이라(§4.9) 안 고르고 온 경우가 정상이다. 그때 대회장으로 떨어지지
     * 않고 `(0.0, 0.0)` 으로 가면 기니만 앞바다 후보가 나온다(#136 리뷰).
     *
     * 둘 다 없으면 **null 이다** — 조회를 아예 하지 않는다. 좌표 없는 대회는 S3 CTA 가
     * 막으므로(§4.6) 실제로는 여기 닿지 않는다.
     */
    private var sheetCenter: Pair<Double, Double>? = null

    /** 위저드 상태로 생성을 요청한다. 같은 조건이면 다시 부르지 않는다. */
    fun generate(wizard: WizardUiState) {
        val request = wizard.toRequestOrNull(demoContestId) ?: run {
            // 화면은 어떤 이유로든 무반응이면 안 된다. 예전에는 위저드 CTA 가 막아 줘서
            // 여기 닿지 않았지만, canonical id 가 없는 대회(번들·오프라인)라는 **새 원인**이
            // 생겼다 — 그대로 두면 스피너가 영원히 돈다 (#66 리뷰)
            _uiState.update { it.copy(phase = ResultUiState.Phase.ERROR, errorMessage = wizard.blockedReason()) }
            return
        }
        // 이전 여정의 숙소 좌표가 남지 않게 매번 다시 정한다. 숙소 > 대회장 순이다(§4.10).
        sheetCenter = wizard.stay?.let { it.lat to it.lng }
            ?: wizard.race?.let { race ->
                val lat = race.lat
                val lng = race.lng
                if (lat != null && lng != null) lat to lng else null
            }
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

    // ── 저장 (SPEC §4.10 · API 명세 §5-2) ──────────────────────

    /**
     * [이 동선 저장하기]. 편집을 마친 결과를 통째로 보낸다. (§5-2)
     *
     * **RACE 블록도 그대로 실어 보낸다.** 서버가 `blockType=RACE` 를 확인한 뒤 저장 시점
     * canonical 대회로 다시 채우므로 앱이 걸러 낼 이유가 없다(이슈 #204 · 선경 님 확정).
     *
     * 성공하면 화면이 마이[동선]으로 옮겨 간다 — 그래서 문구를 상태에 실어 보낸다.
     */
    fun onSave() {
        val state = _uiState.value
        val result = state.result ?: return
        // 연타로 같은 동선이 두 번 나가는 것을 막는다. 교체 규칙이 있어 두 벌이 쌓이지는
        // 않지만, 두 번째 응답이 늦게 와서 이미 옮겨 간 화면을 다시 건드린다.
        if (state.save is SaveItineraryState.Saving) return

        // 기다리는 사이 세션이 바뀌면 그 결과는 남의 것이다 (S8 `onSaveCourse` 와 같은
        // 장치 · #166 리뷰). 저장은 계정에 쌓는 일이라 여기가 특히 중요하다.
        val epoch = SessionStore.sessionEpoch
        // 보내는 중에 내용을 고치면 `save` 가 Idle 로 풀려 다시 누를 수 있다. 앞의 요청을
        // 안 끊으면 **고치기 전 동선**의 응답이 뒤늦게 화면을 옮긴다.
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            _uiState.update { it.copy(save = SaveItineraryState.Saving) }
            val next = try {
                val outcome = repository.save(result)
                SaveItineraryState.Saved(
                    id = outcome.id,
                    replaced = outcome.replaced,
                    // 새로 담은 것과 덮어쓴 것은 사용자에게 다른 일이다 (§5-2)
                    message = if (outcome.replaced) {
                        "이전에 저장한 동선을 새로 바꿨어요."
                    } else {
                        "마이에 저장했어요."
                    },
                )
            } catch (e: ApiException) {
                // 게스트는 문구가 아니라 모달이다 — 로그인은 화면을 옮겨야 끝나는 일이다
                if (e is ApiException.Http && e.needsLogin) SaveItineraryState.NeedsLogin
                else SaveItineraryState.Failed(e.saveMessage())
            }
            // **`NeedsLogin` 은 통과시킨다.** 세대가 오르는 흔한 이유가 바로 "세션이
            // 죽었다" 이다 — `401` 을 받은 `TokenAuthenticator` 가 재발급에 실패하면
            // `signOut()` 이 응답보다 먼저 세대를 올린다. 그 결과까지 버리면 정작
            // 로그인하라는 말을 못 한다(#166 리뷰 · S8 과 같은 판단).
            //
            // **버리더라도 버튼은 푼다.** `Saving` 인 채로 두면 "저장 중…" 이 굳는다.
            if (epoch != SessionStore.sessionEpoch && next !is SaveItineraryState.NeedsLogin) {
                _uiState.update {
                    if (it.save is SaveItineraryState.Saving) it.copy(save = SaveItineraryState.Idle) else it
                }
                return@launch
            }
            _uiState.update { it.copy(save = next) }
        }
    }

    /**
     * 성공을 화면이 **한 번 쓰고 나면 비운다.** (SPEC §4.10 · #214 리뷰)
     *
     * [SaveItineraryState.Saved] 를 그대로 두면 마이에서 뒤로 왔을 때 S7 이 다시
     * 합성되면서 **같은 상태로 또 마이로 튕긴다** — 뒤로가기가 막힌다. 화면이 이미
     * 떠났으므로 여기서는 지우기만 한다.
     *
     * 내비게이션 쪽에서 위저드 그래프를 백스택에서 걷는 것과 **둘 다 필요하다.** 상태만
     * 비우면 백스택에 남은 위저드로 돌아가 이미 저장한 동선을 다시 편집하게 되고,
     * 백스택만 걷으면 다른 경로로 재진입할 때 같은 일이 난다.
     */
    fun onSavedHandled() {
        _uiState.update {
            if (it.save is SaveItineraryState.Saved) it.copy(save = SaveItineraryState.Idle) else it
        }
    }

    /** 로그인 유도 모달을 닫았다. 돌아와서 다시 누르면 된다 (D-27). */
    fun onLoginPromptDismiss() {
        _uiState.update {
            if (it.save is SaveItineraryState.NeedsLogin) it.copy(save = SaveItineraryState.Idle) else it
        }
    }

    /**
     * 일자 탭. **활성화 + 지도 재계산 + 첫 핀 활성**이다. (SPEC §4.10)
     *
     * 첫 핀을 골라 두는 것이 계약이다 — 일자를 바꿨는데 아무것도 안 골라져 있으면
     * 타임라인에 강조된 카드가 없어 어디부터 보는지 알 수 없다.
     *
     * 좌표 있는 블록이 하나도 없는 날이면 `null` 이다. 그때는 지도 자리에 안내만 뜬다.
     */
    fun onDaySelect(index: Int) {
        _uiState.update { state ->
            val moved = state.copy(activeDayIndex = index, activeBlockId = null)
            moved.copy(activeBlockId = moved.mapPins.firstOrNull()?.id)
        }
    }

    /**
     * 지도 핀을 탭했다. (SPEC §3-8 · §4.10 — "핀 탭 → 카드로 스크롤")
     *
     * **편집 모드에서는 아무것도 하지 않는다.** §4.10 이 "편집 모드 중 동기화 중단" 을
     * 요구한다 — 편집 중에는 사용자가 행을 옮기고 지우는 중이라, 그때 카드가 저절로
     * 스크롤되면 누르려던 버튼이 발밑에서 움직인다.
     *
     * **같은 핀을 다시 눌러도 풀지 않는다.** 계약은 "핀 탭 → 해당 항목 활성" 뿐이고,
     * 일자를 고르면 항상 첫 핀이 활성이므로 "아무것도 안 골라진 상태" 는 이 화면에 없다.
     *
     * 처음에는 눌러서 놓을 수 있게 두면 카메라가 전체 bounds 로 돌아간다고 적었는데
     * **사실이 아니었다.** [com.runninggu.app.ui.map.cameraCommandFor] 는 다음 활성이
     * null 이면 `None` 을 돌려주므로, 강조만 사라지고 카메라는 확대된 채 남는다(#208 리뷰).
     */
    fun onPinClick(blockId: String) {
        _uiState.update { if (it.isEditing) it else it.copy(activeBlockId = blockId) }
    }

    /**
     * 타임라인 카드를 탭했다. (SPEC §4.10 — "카드 탭 → 핀 카메라 이동")
     *
     * 핀 탭의 반대 방향이다. 활성이 바뀌면 [com.runninggu.app.ui.map.MapScene] 규칙이
     * 카메라를 그 좌표로 옮긴다.
     *
     * 좌표 없는 블록을 눌러도 **활성으로 두지 않는다** — 카메라가 갈 곳이 없어서
     * 지도는 그대로인데 카드만 강조되면 왜 안 움직이는지 알 수 없다.
     */
    fun onCardClick(blockId: String) {
        _uiState.update { state ->
            if (state.isEditing) return@update state
            if (state.mapPins.none { it.id == blockId }) return@update state
            state.copy(activeBlockId = blockId)
        }
    }

    /**
     * 사용자가 타임라인을 스크롤해 이 카드가 **중앙 밴드**에 들어왔다.
     * (SPEC §4.10 — "LazyList 스크롤 중앙 밴드(상하 45% 제외)에 든 카드 자동 활성")
     *
     * 탭과 결과는 같지만 **부르는 쪽이 다르다.** 탭은 한 번이고 이쪽은 스크롤이 굴러가는
     * 동안 계속 들어온다 — 그래서 [onCardClick] 와 나눠 두고, 화면 쪽에서도 값이 바뀔
     * 때만 부른다.
     *
     * 좌표 없는 카드를 지나쳐도 활성을 옮기지 않는 것은 [onCardClick] 와 같은 이유다.
     * 카메라가 갈 곳이 없어 지도는 그대로인데 강조만 옮겨 다니면 지도가 고장 난 것처럼
     * 보인다. **가운데 있는 것과 다른 카드가 강조된 채로 지나가는 편이 낫다.**
     *
     * 편집 중에는 화면이 아예 부르지 않지만(§4.10 동기화 중단) 여기서도 한 번 막는다 —
     * 부르는 자리가 여럿이면 한 곳이 빠졌을 때 조용히 뚫린다.
     */
    fun onCardCentered(blockId: String) {
        _uiState.update { state ->
            if (state.isEditing) return@update state
            if (state.activeBlockId == blockId) return@update state
            if (state.mapPins.none { it.id == blockId }) return@update state
            state.copy(activeBlockId = blockId)
        }
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
            state.copy(
                result = result.copy(days = transform(result.days, state.activeDayIndex)),
                // 내용이 바뀌면 이전 저장 결과 문구는 거짓말이 된다. "저장하지 못했어요" 가
                // 남은 채 장소를 바꾸면, 방금 바꾼 것이 실패한 줄로 읽힌다.
                save = SaveItineraryState.Idle,
            )
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
        // 기준 좌표가 없으면 후보를 부를 수 없다. 빈 시트를 여는 대신 조회를 건너뛴다.
        val (lat, lng) = sheetCenter ?: run {
            _uiState.update { it.copy(sheet = sheet.copy(phase = CandidateSheetState.Phase.EMPTY)) }
            return
        }
        viewModelScope.launch {
            val outcome = runCatchingUnlessCancelled { poiRepository.search(sheet.category, lat, lng) }
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
            val outcome = runCatchingUnlessCancelled { repository.generate(request) }
            _uiState.value = outcome.fold(
                onSuccess = { result ->
                    val loaded = ResultUiState(
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
                    // 첫 일자도 "고른 일자" 다 — 탭을 눌렀을 때와 같이 첫 핀을 활성으로 둔다
                    // (SPEC §4.10). 안 그러면 처음 화면만 강조된 카드가 없다
                    loaded.copy(activeBlockId = loaded.mapPins.firstOrNull()?.id)
                },
                onFailure = { cause ->
                    // 네트워크·timeout·4xx/5xx 는 Error 이며 Empty 로 강등하지 않는다 (API 명세 §5-1).
                    val inactive = cause.apiErrorCode() == ApiErrorCode.CONTEST_INACTIVE
                    ResultUiState(
                        phase = ResultUiState.Phase.ERROR,
                        event = request.event,
                        region = lastRegion,
                        errorMessage = if (inactive) {
                            // 원천에서 사라진 대회다. 사용자가 할 일은 다른 대회를 고르는 것이지
                            // 다시 누르는 게 아니다 (결정-46 · 결정-53).
                            "정보 제공이 끝난 대회예요. 다른 대회를 골라 주세요."
                        } else {
                            "동선을 만들지 못했어요."
                        },
                        canRetry = !inactive,
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

