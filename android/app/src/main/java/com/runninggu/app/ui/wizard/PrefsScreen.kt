package com.runninggu.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.PoiCategory

/**
 * S5 종목·취향. (SPEC §4.8 · AP-11)
 *
 * 위저드 둘째 화면이다. [viewModel]은 wizard 그래프 스코프라 S4에서 고른 일정이 그대로 이어진다.
 *
 * 여기서 고른 종목·취향이 동선 엔진(§5.6)의 입력이 된다 — 종목은 회복 룰(§5.1)을,
 * 취향은 카테고리 풀(§5.6-2)과 테마 우선순위(§5.6-5)를 정한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrefsScreen(
    onBack: () -> Unit,
    onNext: () -> Unit,
    viewModel: WizardViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
            // 대회를 못 실었으면 다음으로 갈 수 없다 (#189 후속).
            if (state.contestPhase == WizardUiState.Phase.LOADED) {
                NextBar(enabled = state.canProceedFromPrefs, onClick = onNext)
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            WizardContestGate(state = state, onRetry = viewModel::load) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Text(
                        text = "어떻게 뛰고, 뭘 좋아하세요?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(Modifier.height(24.dp))
                    EventSection(state = state, onSelect = viewModel::onEventSelect)

                    Spacer(Modifier.height(28.dp))
                    ThemeSection(state = state, onToggle = viewModel::onThemeToggle)

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** 종목 세그먼트. 4종을 상시 노출하고 대회에 없는 종목도 고를 수 있다. (SPEC §4.8) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventSection(state: WizardUiState, onSelect: (EventType) -> Unit) {
    // 세그먼트 표시 순서는 5K|10K|하프|풀 로, §5.4 의 표시 순서(풀>하프>10K>5K)와 반대다.
    val options = EventType.entries.reversed()

    SectionLabel("종목 · 회복강도 ${state.intensity}")

    Spacer(Modifier.height(10.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, event ->
            SegmentedButton(
                selected = event == state.event,
                onClick = { onSelect(event) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(event.label)
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = if (state.isEventInRace) {
            "대회에서 가져왔어요 · 바꿀 수 있어요"
        } else {
            "이 대회 종목엔 없지만 선택할 수 있어요"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state.showsRecoveryNotice) {
        Spacer(Modifier.height(14.dp))
        RecoveryNotice(event = state.event)
    }
}

/**
 * 회복 안내. 하프·풀에만 뜬다. (SPEC §4.8 · §5.1 `noHard`)
 *
 * 실제 동선에서 D+1 이 어떻게 짜이는지 미리 알려주는 자리라, 엔진이 정말 그렇게 만든다
 * (§5.6-4 의 D+N 회복 분기).
 */
@Composable
private fun RecoveryNotice(event: EventType) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "${event.label}는 완주 다음날 회복이 중요해요. " +
                "D+1은 고강도 일정을 빼고 온천·가벼운 산책 위주로 동선을 짭니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** 여행 취향 칩. 복수 선택이고 0개면 CTA 가 막힌다. (SPEC §4.8 · §5.3) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSection(state: WizardUiState, onToggle: (PoiCategory) -> Unit) {
    SectionLabel("여행 취향")

    Spacer(Modifier.height(4.dp))
    Text(
        text = "고른 취향 위주로 장소를 채워드려요. 하나 이상 골라주세요.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 숙소는 검색 전용이라 칩에 없다 (§5.3).
        PoiCategory.selectable.forEach { category ->
            FilterChip(
                selected = category in state.themes,
                onClick = { onToggle(category) },
                label = { Text(category.label) },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
