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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.runninggu.app.ui.common.EmptyState
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState

/**
 * S7 추천 동선 결과 — 조회 모드. (SPEC §4.10 · AP-11)
 *
 * 위저드에서 고른 조건으로 만든 동선을 일자별로 보여준다.
 *
 * 이번 범위는 **조회**다. 아래 셋은 후속 작업에서 붙인다.
 * - 상단 지도(번호 핀·폴리라인) — AP-03 카카오맵이 필요하다
 * - 편집 모드와 후보 시트 — `ItineraryEdits`(§5.7)는 준비돼 있다
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
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        // TODO(AP-03): 상단 지도. 활성 일자의 번호 핀·폴리라인 (SPEC §3-8 · §4.10).
        MapPlaceholder()

        Column(Modifier.padding(horizontal = 20.dp)) {
            state.itinerary?.recovery?.let {
                Spacer(Modifier.height(16.dp))
                RecoveryBadge(label = it.label, text = it.text)
            }

            Spacer(Modifier.height(16.dp))
            SummaryRow(title = state.title, placeCount = state.placeCount)

            Spacer(Modifier.height(14.dp))
            DayTabs(state = state, onSelect = onDaySelect)

            state.activeDay?.let { day ->
                Spacer(Modifier.height(18.dp))
                DayNote(day.note)

                Spacer(Modifier.height(12.dp))
                Timeline(day)
            }

            Spacer(Modifier.height(20.dp))
            CourseLinkCard(targetKm = state.courseTargetKm, onClick = onOpenCourses)

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
                leadingIcon = if (state.isRecoveryDay(day)) {
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
