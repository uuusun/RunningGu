package com.runninggu.app.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.runninggu.app.data.model.PoiItem

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
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
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
 * - USER: 번호 + 제목 + "{시간}·{장소}·{카테고리}" + 순서·삭제 버튼
 * - RACE: 잠금 아이콘과 "관리자 업데이트". **순서·교체·삭제 버튼을 아예 그리지 않는다.**
 *
 * 버튼을 숨기는 게 본 방어선이고, [ItineraryEdits]의 거부는 그래도 새어 들어온 경우를 막는
 * 안전망이다 — 목업은 이 방어가 없어 대회 블록이 삭제됐다(대조표 B4).
 */
@Composable
private fun EditList(
    day: ItineraryDay,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onReplace: (ItineraryBlock) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        day.blocks.forEachIndexed { index, block ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NumberRail(index + 1)
                    Spacer(Modifier.width(10.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            text = block.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (ItineraryEdits.canEdit(block)) {
                                listOfNotNull(block.time, block.place?.name, block.catKey.label)
                                    .joinToString(" · ")
                            } else {
                                "관리자 업데이트"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (ItineraryEdits.canEdit(block)) {
                        // TODO(AP-11): 그립 드래그로 바꾼다. 지금은 한 칸씩 옮기는 버튼이다.
                        IconButton(
                            onClick = { onMove(index, index - 1) },
                            enabled = index > 0,
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "위로")
                        }
                        IconButton(
                            onClick = { onMove(index, index + 1) },
                            enabled = index < day.blocks.lastIndex,
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "아래로")
                        }
                        // 회복 안내처럼 조회 카테고리가 없는 블록은 교체 대상이 아니다.
                        if (block.catKey.toPoiCategoryOrNull() != null) {
                            IconButton(onClick = { onReplace(block) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "교체")
                            }
                        }
                        IconButton(onClick = { onRemove(block.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "삭제")
                        }
                    } else {
                        // 잠금만 보이고 조작 버튼은 없다 (SPEC §4.10 "그립·교체·삭제 미노출").
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
