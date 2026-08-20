package com.runninggu.app.ui.wizard

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.BlockType
import com.runninggu.app.domain.ItineraryBlock
import com.runninggu.app.domain.ItineraryDay
import com.runninggu.app.domain.ItineraryEdits
import com.runninggu.app.domain.PoiCategory
import com.runninggu.app.ui.common.EmptyState
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState
import com.runninggu.app.ui.common.SourceBadge
import kotlinx.coroutines.launch

/**
 * S7 추천 동선 결과. (SPEC §4.10 · AP-11)
 *
 * 서버가 만든 동선을 일자별로 보여주고(조회), 저장 전 USER 블록을 로컬 편집한다(편집 모드).
 * 동선 생성은 서버 몫이다(결정-41).
 *
 * 아래 둘은 후속 작업에서 붙인다.
 * - 상단 지도(번호 핀·폴리라인) — AP-03 카카오맵이 필요하다
 * - 저장 CTA 의 실제 저장 — `POST /api/itineraries`(AP-14)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    onBack: () -> Unit,
    onChangeConditions: () -> Unit,
    onOpenCourses: (targetKm: Double) -> Unit,
    wizardViewModel: WizardViewModel,
    viewModel: ResultViewModel,
    modifier: Modifier = Modifier,
) {
    val wizard by wizardViewModel.uiState.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(wizard) { viewModel.generate(wizard) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("추천 동선", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
        bottomBar = {
            if (state.phase == ResultUiState.Phase.CONTENT) {
                SaveBar()
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when (state.phase) {
                ResultUiState.Phase.LOADING -> LoadingState("동선 짜는 중…")

                ResultUiState.Phase.EMPTY -> Column {
                    EmptyState("동선이 아직 없어요.")
                    TextButton(
                        onClick = onChangeConditions,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("조건 바꾸기") }
                }

                ResultUiState.Phase.ERROR -> ErrorState(
                    message = state.errorMessage.orEmpty(),
                    onRetry = viewModel::retry,
                )

                ResultUiState.Phase.CONTENT -> Content(
                    state = state,
                    onDaySelect = viewModel::onDaySelect,
                    onOpenCourses = { onOpenCourses(state.courseTargetKm) },
                    onToggleEdit = viewModel::onToggleEdit,
                    onRemoveBlock = viewModel::onRemoveBlock,
                    onMoveBlock = viewModel::onMoveBlock,
                    onReplaceBlock = viewModel::onReplaceBlock,
                    onAddPlace = viewModel::onAddPlace,
                )
            }

            state.sheet?.let { sheet ->
                CandidateSheet(
                    sheet = sheet,
                    onDismiss = viewModel::onSheetDismiss,
                    onCategorySelect = viewModel::onSheetCategorySelect,
                    onSelect = viewModel::onCandidateSelect,
                    onRetry = viewModel::onSheetRetry,
                )
            }
        }
    }
}

@Composable
private fun Content(
    state: ResultUiState,
    onDaySelect: (Int) -> Unit,
    onOpenCourses: () -> Unit,
    onToggleEdit: () -> Unit,
    onRemoveBlock: (String) -> Unit,
    onMoveBlock: (Int, Int) -> Unit,
    onReplaceBlock: (ItineraryBlock) -> Unit,
    onAddPlace: () -> Unit,
) {
    // 스와이프로 삭제 버튼을 연 행. 화면에 하나만 열려 있고, 바깥을 건드리면 닫힌다.
    var openedBlockId by remember(state.isEditing) { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .pointerInput(openedBlockId) {
                if (openedBlockId == null) return@pointerInput
                awaitPointerEventScope {
                    // Final 패스라 버튼(clickable)이 이미 가져간 터치는 보이지 않는다 —
                    // 열린 행의 [삭제]를 누른 것까지 닫아버리면 삭제가 실행되지 않는다.
                    // 아무도 가져가지 않은 터치 = 빈 곳·다른 행·스크롤이므로 그때 닫는다.
                    awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Final)
                    openedBlockId = null
                }
            },
    ) {
        // TODO(AP-03): 상단 지도. 활성 일자의 번호 핀·폴리라인 (SPEC §3-8 · §4.10).
        MapPlaceholder()

        Column(Modifier.padding(horizontal = 20.dp)) {
            state.result?.recovery?.let {
                Spacer(Modifier.height(16.dp))
                RecoveryBadge(label = it.label, text = it.note)
            }

            Spacer(Modifier.height(16.dp))
            SummaryRow(title = state.title, placeCount = state.placeCount)

            Spacer(Modifier.height(14.dp))
            DayTabs(state = state, onSelect = onDaySelect)

            state.activeDay?.let { day ->
                Spacer(Modifier.height(18.dp))
                DayHeader(label = day.label, isEditing = state.isEditing, onToggleEdit = onToggleEdit)

                Spacer(Modifier.height(10.dp))
                if (state.isEditing) {
                    EditNotice()
                    Spacer(Modifier.height(10.dp))
                    EditList(
                        day = day,
                        openedId = openedBlockId,
                        onOpenedChange = { openedBlockId = it },
                        onRemove = onRemoveBlock,
                        onMove = onMoveBlock,
                        onReplace = onReplaceBlock,
                    )
                    Spacer(Modifier.height(10.dp))
                    AddPlaceButton(onClick = onAddPlace)
                } else {
                    DayNote(day.note)
                    Spacer(Modifier.height(12.dp))
                    Timeline(day)
                }
            }

            // 연계 카드는 조회 모드에만 둔다 (SPEC §4.10 — "조회 모드 하단 연계 카드").
            if (!state.isEditing) {
                Spacer(Modifier.height(20.dp))
                CourseLinkCard(targetKm = state.courseTargetKm, onClick = onOpenCourses)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 회복 배지. `noHard` 종목에만 나온다. (SPEC §4.10 · §5.6-6) */
@Composable
private fun RecoveryBadge(label: String, text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/** "{지역} n박 n+1일" + "{n}곳". (SPEC §4.10) */
@Composable
private fun SummaryRow(title: String, placeCount: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "${placeCount}곳",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 일자 탭. 회복일은 구분 스타일을 준다. (SPEC §4.10) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayTabs(state: ResultUiState, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.days.forEachIndexed { index, day ->
            FilterChip(
                selected = index == state.activeDayIndex,
                onClick = { onSelect(index) },
                label = { Text("${day.label} · ${day.dateLabel}") },
                leadingIcon = if (state.isRecoveryDay(index)) {
                    { RecoveryDot() }
                } else {
                    null
                },
            )
        }
    }
}

/** 회복일 표시 점. 지도 핀 주황과 같은 뜻이다. (SPEC §4.10) */
@Composable
private fun RecoveryDot() {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary),
    )
}

/** 일자 라벨 줄 + [편집]↔[완료]. (SPEC §4.10) */
@Composable
private fun DayHeader(label: String, isEditing: Boolean, onToggleEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        TextButton(onClick = onToggleEdit) {
            Text(if (isEditing) "완료" else "편집")
        }
    }
}

/** 편집 모드 안내. 대회 일정을 왜 못 바꾸는지 미리 알린다. (SPEC §4.10) */
@Composable
private fun EditNotice() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "일반 장소는 순서 변경 · 교체 · 삭제할 수 있어요. 대회 일정은 변경할 수 없어요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * 편집 목록. (SPEC §4.10 · §5.7)
 *
 * 행 종류가 둘이다.
 * - USER: 번호 + 제목 + "{시간}·{장소}·{카테고리}" + 교체 · 휴지통 + **오른쪽 끝 그립**.
 *   순서 변경은 그립을 **길게 눌러 끄는** 드래그 — 이웃 행의 절반을 넘을 때마다
 *   실제 목록을 한 칸씩 옮기므로 놓는 순간 이미 반영돼 있다.
 *   삭제는 두 길이다 — 휴지통 탭, 또는 행을 **왼쪽으로 스와이프**하면 나타나는
 *   빨간 [삭제] 버튼. 열린 행은 화면에 하나뿐이고([openedId]), 바깥을 건드리면 닫힌다.
 * - RACE: 잠금 아이콘과 "관리자 업데이트". **그립·교체·스와이프를 아예 주지 않는다.**
 *
 * 버튼을 숨기는 게 본 방어선이고, [ItineraryEdits]의 거부는 그래도 새어 들어온 경우를 막는
 * 안전망이다 — 목업은 이 방어가 없어 대회 블록이 삭제됐다(대조표 B4).
 */
@Composable
private fun EditList(
    day: ItineraryDay,
    openedId: String?,
    onOpenedChange: (String?) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onReplace: (ItineraryBlock) -> Unit,
) {
    // 드래그 제스처 코루틴이 여러 리컴포지션에 걸쳐 살아 있으므로 최신 목록을 State 로 읽는다.
    val blocks by rememberUpdatedState(day.blocks)
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val rowHeights = remember { mutableStateMapOf<String, Int>() }
    val rowSpacing = with(LocalDensity.current) { 8.dp.toPx() }

    /**
     * 방금 이동을 요청한 위치. [blocks] 가 갱신되기 전까지 추가 판정을 쉬게 한다.
     *
     * `onMove` 는 ViewModel → StateFlow → 리컴포지션을 거쳐야 [blocks] 에 반영된다.
     * 한 프레임에 드래그 이벤트가 여러 번 오면 **직전 이동이 빠진 목록**으로 이웃 높이를
     * 읽게 되는데, USER 행(IconButton 48dp)과 RACE 행(잠금 아이콘 18dp)은 높이가 달라서
     * 방금 옮긴 것을 도로 되돌릴 수 있다.
     */
    var pendingMoveFrom by remember { mutableIntStateOf(NO_PENDING_MOVE) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        day.blocks.forEachIndexed { index, block ->
            // key 로 행 컴포지션을 id 에 묶는다 — 드래그 중 목록이 재정렬돼도
            // 그립의 제스처 코루틴이 끊기지 않고 행을 따라간다.
            key(block.id) {
                val isDragging = block.id == draggingId
                val editable = ItineraryEdits.canEdit(block)
                val scope = rememberCoroutineScope()
                // 왼쪽 스와이프로 여는 삭제 버튼의 노출량. 0(닫힘) ~ -deleteWidthPx(열림).
                val deleteWidthPx = with(LocalDensity.current) { DELETE_REVEAL_WIDTH.toPx() }
                val reveal = remember(block.id) { Animatable(0f) }
                // 열린 만큼 행의 오른쪽을 깎는다. reveal 은 0(닫힘) ~ 음수(열림).
                val revealWidth = with(LocalDensity.current) { (-reveal.value).toDp() }
                // 다른 행이 열렸거나 바깥을 터치해 닫혔으면 이 행도 제자리로 돌아간다.
                LaunchedEffect(openedId) {
                    if (openedId != block.id && reveal.value != 0f) reveal.animateTo(0f)
                }

                // 삭제된 행의 높이를 남기지 않는다 — `nextBlockId` 가 `max + 1` 이라 id 가
                // 재사용되고(b5 삭제 후 추가하면 다시 b5), 옛 높이가 새 행 판정에 쓰인다.
                DisposableEffect(block.id) {
                    onDispose { rowHeights.remove(block.id) }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { rowHeights[block.id] = it.height }
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f },
                ) {
                    // 닫혀 있을 때는 그리지 않는다 — 행 모서리(둥근 부분) 틈으로 빨강이 비친다.
                    if (editable && reveal.value < -0.5f) {
                        // 행 뒤에 숨어 있다가 왼쪽 스와이프로 드러나는 삭제 버튼 (SPEC §4.10 삭제).
                        Box(
                            Modifier
                                .matchParentSize()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.error),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Box(
                                Modifier
                                    .width(DELETE_REVEAL_WIDTH)
                                    .fillMaxHeight()
                                    .clickable {
                                        onOpenedChange(null)
                                        onRemove(block.id)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "삭제",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onError,
                                )
                            }
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = if (isDragging) 6.dp else 1.dp,
                        shadowElevation = if (isDragging) 6.dp else 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 삭제 버튼 자리를 행을 **옆으로 밀어서** 내면 제목이 화면 밖으로
                            // 잘려 어떤 일정을 지우는지 안 보인다. 그래서 미는 대신 **오른쪽을 깎는다** —
                            // 행의 왼쪽 끝은 제자리에 있고 깎인 만큼 빨간 버튼이 드러난다.
                            .padding(end = revealWidth)
                            .then(
                                if (editable) {
                                    Modifier.pointerInput(block.id) {
                                        detectHorizontalDragGestures(
                                            onHorizontalDrag = { change, dx ->
                                                change.consume()
                                                scope.launch {
                                                    reveal.snapTo(
                                                        (reveal.value + dx).coerceIn(-deleteWidthPx, 0f),
                                                    )
                                                }
                                            },
                                            // 절반 넘게 열었으면 완전히 열고, 아니면 도로 닫는다.
                                            onDragEnd = {
                                                val opened = reveal.value < -deleteWidthPx / 2f
                                                // 애니메이션보다 상태를 먼저 알린다 — 그래야 앞서
                                                // 열려 있던 다른 행이 곧바로 닫히기 시작한다.
                                                if (opened) {
                                                    onOpenedChange(block.id)
                                                } else if (openedId == block.id) {
                                                    onOpenedChange(null)
                                                }
                                                scope.launch {
                                                    reveal.animateTo(if (opened) -deleteWidthPx else 0f)
                                                }
                                            },
                                            onDragCancel = {
                                                if (openedId == block.id) onOpenedChange(null)
                                                scope.launch { reveal.animateTo(0f) }
                                            },
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NumberRail(index + 1)
                            Spacer(Modifier.width(10.dp))

                            Column(Modifier.weight(1f)) {
                                // 스와이프로 폭이 줄어들 때 줄바꿈이 생기면 행 높이가 튀고,
                                // 그 높이로 판정하는 그립 드래그까지 흔들린다. 그래서 한 줄로 고정한다.
                                Text(
                                    text = block.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (editable) {
                                        listOfNotNull(block.time, block.place?.name, block.catKey.label)
                                            .joinToString(" · ")
                                    } else {
                                        "관리자 업데이트"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            if (editable) {
                                // 회복 안내처럼 조회 카테고리가 없는 블록은 교체 대상이 아니다.
                                if (block.catKey.toPoiCategoryOrNull() != null) {
                                    IconButton(onClick = { onReplace(block) }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "교체")
                                    }
                                }
                                // 삭제는 두 길이다 — 휴지통 탭, 또는 행을 왼쪽으로 스와이프.
                                IconButton(onClick = { onRemove(block.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "삭제")
                                }
                                Spacer(Modifier.width(4.dp))
                                DragGrip(
                                    modifier = Modifier.pointerInput(block.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggingId = block.id
                                                dragOffsetY = 0f
                                                pendingMoveFrom = NO_PENDING_MOVE
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffsetY += amount.y
                                                val from = blocks.indexOfFirst { it.id == block.id }
                                                if (from == -1) return@detectDragGesturesAfterLongPress
                                                // 직전 이동이 아직 목록에 반영되지 않았다 — 낡은 이웃
                                                // 높이로 판정하면 방금 옮긴 것을 되돌릴 수 있다.
                                                if (from == pendingMoveFrom) {
                                                    return@detectDragGesturesAfterLongPress
                                                }
                                                pendingMoveFrom = NO_PENDING_MOVE

                                                // 이웃 행의 절반을 넘으면 실제로 한 칸 옮긴다. 옮긴 만큼
                                                // 시각 오프셋을 되돌려 행이 손가락 밑에 그대로 남는다.
                                                val below = blocks.getOrNull(from + 1)?.let { rowHeights[it.id] }
                                                if (below != null && dragOffsetY > (below + rowSpacing) / 2f) {
                                                    onMove(from, from + 1)
                                                    pendingMoveFrom = from
                                                    dragOffsetY -= below + rowSpacing
                                                    return@detectDragGesturesAfterLongPress
                                                }
                                                val above = blocks.getOrNull(from - 1)?.let { rowHeights[it.id] }
                                                if (above != null && dragOffsetY < -(above + rowSpacing) / 2f) {
                                                    onMove(from, from - 1)
                                                    pendingMoveFrom = from
                                                    dragOffsetY += above + rowSpacing
                                                }
                                            },
                                            onDragEnd = {
                                                draggingId = null
                                                dragOffsetY = 0f
                                                pendingMoveFrom = NO_PENDING_MOVE
                                            },
                                            onDragCancel = {
                                                draggingId = null
                                                dragOffsetY = 0f
                                                pendingMoveFrom = NO_PENDING_MOVE
                                            },
                                        )
                                    },
                                )
                                Spacer(Modifier.width(4.dp))
                            } else {
                                // 잠금만 보이고 조작은 없다 (SPEC §4.10 "그립·교체·삭제 미노출").
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "변경할 수 없는 일정",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 왼쪽 스와이프로 드러나는 삭제 버튼의 폭. */
private val DELETE_REVEAL_WIDTH = 84.dp

/** 대기 중인 이동이 없음. [Int] 인덱스와 섞이지 않게 음수를 쓴다. */
private const val NO_PENDING_MOVE = -1

/** 순서 변경 그립. **길게 누른 채 끌면** 행이 따라온다. (SPEC §4.10 "그립") */
@Composable
private fun DragGrip(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Default.Menu,
        contentDescription = "길게 눌러 순서 변경",
        modifier = modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 편집 목록 하단의 [장소 추가]. 후보 시트를 추가 모드로 연다. (SPEC §4.10) */
@Composable
private fun AddPlaceButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("장소 추가")
    }
}

/**
 * 후보 시트. (SPEC §4.10 — ModalBottomSheet 📱전환)
 *
 * 헤더 "{카테고리} {교체|추가} · 인근" + 소스 배지. **추가 모드에만** 카테고리 칩
 * (취향 6종+숙소)이 나온다. 후보는 8건이고 [선택]으로 교체/추가 후 닫힌다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateSheet(
    sheet: CandidateSheetState,
    onDismiss: () -> Unit,
    onCategorySelect: (PoiCategory) -> Unit,
    onSelect: (PoiItem) -> Unit,
    onRetry: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // 작은 화면·가로 모드에서 후보 8건이 시트 최대 높이를 넘을 수 있어 스크롤을 준다.
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sheet.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (sheet.source.isNotEmpty() && sheet.phase == CandidateSheetState.Phase.CONTENT) {
                    // 데이터 출처 배지 — LIVE·SAMPLE·SYNTH. (SPEC §6.3 · NFR-2)
                    SourceBadge(sheet.source)
                }
            }

            if (!sheet.isReplace) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PoiCategory.entries.forEach { category ->
                        FilterChip(
                            selected = category == sheet.category,
                            onClick = { onCategorySelect(category) },
                            label = { Text(category.label) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            // 로딩·빈·오류는 다른 화면과 같은 공용 표시를 쓴다 (SPEC §3-5 · AGENTS 2-5).
            when (sheet.phase) {
                CandidateSheetState.Phase.LOADING -> LoadingState("주변 장소 찾는 중…")

                CandidateSheetState.Phase.EMPTY -> EmptyState("주변에 보여드릴 곳이 없어요.")

                CandidateSheetState.Phase.ERROR -> ErrorState(
                    message = "주변 장소를 불러오지 못했어요.",
                    onRetry = onRetry,
                )

                CandidateSheetState.Phase.CONTENT -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sheet.items.forEach { item ->
                        CandidateRow(item = item, onSelect = { onSelect(item) })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 후보 한 건. 이름 + 주소·설명 + [선택]. (SPEC §4.10) */
@Composable
private fun CandidateRow(item: PoiItem, onSelect: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                // 순서는 S6 숙소 목록과 같은 "주소 · 설명" (SPEC §4.9 표기 승계)
                val detail = listOf(item.address, item.description)
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSelect) { Text("선택") }
        }
    }
}

/** 일자 노트. 회복 룰의 dday·dplus 문구가 그대로 온다. (SPEC §5.1) */
@Composable
private fun DayNote(note: String) {
    if (note.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** 시간순 카드 목록. 번호 레일 + 제목·시간 + 태그·장소명 + 설명. (SPEC §4.10) */
@Composable
private fun Timeline(day: ItineraryDay) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        day.blocks.forEachIndexed { index, block ->
            TimelineRow(number = index + 1, block = block)
        }
    }
}

@Composable
private fun TimelineRow(number: Int, block: ItineraryBlock) {
    Row(Modifier.fillMaxWidth()) {
        NumberRail(number)
        Spacer(Modifier.width(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = block.time,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = block.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (block.blockType == BlockType.RACE) {
                        Spacer(Modifier.width(6.dp))
                        // 사용자가 바꿀 수 없는 블록임을 조회 모드에서도 보여준다 (SPEC §4.10 · §5.7).
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "변경할 수 없는 일정",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val place = block.place?.name
                if (place != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 대회·숙소는 태그를 생략한다 (SPEC §4.10).
                        if (block.catKey !in setOf(BlockCategory.RACE, BlockCategory.LODGING)) {
                            CategoryTag(block.catKey)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(text = place, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (block.desc.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = block.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberRail(number: Int) {
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$number",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun CategoryTag(category: BlockCategory) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = category.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * S8 연계 카드. 산책 블록을 뺀 자리를 대신한다. (SPEC §4.10 🔧정책 · 대조표 A3)
 *
 * 목표 거리 기본값은 `min(RECOVERY.walk, 5)km` — 풀은 3km, 나머지는 5km 다.
 */
@Composable
private fun CourseLinkCard(targetKm: Double, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "숙소 주변에서 뛰기·걷기",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "목표 ${targetKm.toInt()}km로 코스를 찾아드려요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "러닝코스에서 보기 →",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 지도 자리. AP-03 에서 카카오맵으로 바뀐다. (SPEC §3-8) */
@Composable
private fun MapPlaceholder() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "지도는 준비 중이에요",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 저장 CTA. (SPEC §4.10) */
@Composable
private fun SaveBar() {
    Surface(shadowElevation = 8.dp) {
        Button(
            // TODO(AP-14): `POST /api/itineraries` 저장 후 마이[동선]으로 이동한다 (API 명세 §5-2).
            onClick = {},
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(52.dp),
        ) {
            Text("이 동선 저장하기", style = MaterialTheme.typography.titleMedium)
        }
    }
}
