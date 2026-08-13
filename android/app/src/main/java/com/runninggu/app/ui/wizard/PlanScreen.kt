package com.runninggu.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runninggu.app.ui.common.LoadingState
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd")

/**
 * S4 일정 선택. (SPEC §4.7 · AP-11)
 *
 * 위저드 첫 화면이다. [viewModel]은 wizard 그래프 스코프라 S5~S7과 같은 인스턴스를 받는다
 * — 생성 위치는 [com.runninggu.app.ui.navigation.RunningGuNavHost] 참고.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    raceId: String,
    onBack: () -> Unit,
    onNext: () -> Unit,
    viewModel: WizardViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(raceId) { viewModel.start(raceId) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("동선 만들기", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
        bottomBar = {
            if (state.race != null) {
                NextBar(enabled = state.canProceed, onClick = onNext)
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            val race = state.race
            if (race == null) {
                LoadingState("불러오는 중…")
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Text(
                        text = "언제 다녀올까요?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${race.name} (${race.date.format(MONTH_DAY)}) 기준으로 짜드릴게요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(20.dp))
                    PatternChips(
                        selected = state.pattern,
                        onSelect = viewModel::onPatternSelect,
                    )

                    Spacer(Modifier.height(20.dp))
                    MiniCalendar(
                        month = YearMonth.from(race.date),
                        raceDate = race.date,
                        isInRange = state::isInRange,
                        // 날짜 탭은 '직접 선택'에서만 받는다 (SPEC §4.7).
                        selectable = state.pattern == TripPattern.CUSTOM,
                        onDateTap = viewModel::onDateTap,
                    )

                    Spacer(Modifier.height(12.dp))
                    MiniCalendarLegend()

                    Spacer(Modifier.height(12.dp))
                    RangeSummary(state)

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** 일정 패턴 칩. 단일 선택이고 기본은 "전후로". (SPEC §4.7 · §5.2) */
@Composable
private fun PatternChips(
    selected: TripPattern,
    onSelect: (TripPattern) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TripPattern.entries.forEach { pattern ->
            FilterChip(
                selected = pattern == selected,
                onClick = { onSelect(pattern) },
                label = {
                    Text(
                        if (pattern.hint.isEmpty()) {
                            pattern.label
                        } else {
                            "${pattern.label} · ${pattern.hint}"
                        },
                    )
                },
            )
        }
    }
}

/** "MM.DD ~ MM.DD · n일" 또는 직접 선택 안내 문구. (SPEC §4.7) */
@Composable
private fun RangeSummary(state: WizardUiState) {
    val text = when {
        state.awaitingEndDate -> "종료일을 눌러주세요"
        state.start == null || state.end == null -> "시작일을 눌러주세요"
        state.start == state.end ->
            "${state.start.format(MONTH_DAY)} · 당일치기"

        else ->
            "${state.start.format(MONTH_DAY)} ~ ${state.end.format(MONTH_DAY)} · ${state.dayCount}일"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = if (state.canProceed) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
}

@Composable
private fun NextBar(enabled: Boolean, onClick: () -> Unit) {
    Surface(shadowElevation = 8.dp) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(52.dp),
        ) {
            Text("다음", style = MaterialTheme.typography.titleMedium)
        }
    }
}
