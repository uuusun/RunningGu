package com.runninggu.app.ui.wizard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runninggu.app.ui.common.EmptyState
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState
import com.runninggu.app.ui.common.SourceBadge
import com.runninggu.app.data.model.PoiItem

/**
 * S6 숙소 선택. (SPEC §4.9 · AP-11)
 *
 * 위저드 마지막 입력 화면이다. **숙소는 선택 사항**이라 건너뛰어도 진행되고, 그때는 서버가
 * 대회장 중심으로 슬롯을 채운다.
 *
 * CTA 를 누르면 S7 로 가고, 거기서 `POST /api/itineraries/generate` 를 1회 호출한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayScreen(
    onBack: () -> Unit,
    onNext: () -> Unit,
    wizardViewModel: WizardViewModel,
    viewModel: StayViewModel,
    modifier: Modifier = Modifier,
) {
    val wizard by wizardViewModel.uiState.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 기준점은 대회장이다. 좌표는 아직 RaceSummary 에 없어 0,0 이 가고 Fake 가 무시한다.
    // TODO(AP-14): `GET /contests/{id}` 의 lat/lng 를 넘긴다 (API 명세 §3-4).
    LaunchedEffect(wizard.race?.id) {
        wizard.race?.let { viewModel.start(lat = 0.0, lng = 0.0) }
    }

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
        bottomBar = { StayCta(hasStay = wizard.stay != null, onClick = onNext) },
    ) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "어디서 묵을까요?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "건너뛰면 대회장 주변으로 잡아드려요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("숙소 검색 · 카카오 로컬") },
            )

            if (state.source.isNotEmpty() && state.phase == StayUiState.Phase.CONTENT) {
                Spacer(Modifier.height(8.dp))
                SourceBadge(state.source)
            }

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxSize()) {
                when (state.phase) {
                    StayUiState.Phase.LOADING -> LoadingState("숙소를 찾는 중…")

                    StayUiState.Phase.EMPTY -> EmptyState(
                        title = "검색 결과가 없어요.",
                        description = "다른 이름으로 찾아보거나 건너뛰어도 괜찮아요.",
                    )

                    StayUiState.Phase.ERROR -> ErrorState(
                        message = state.errorMessage.orEmpty(),
                        onRetry = viewModel::retry,
                    )

                    StayUiState.Phase.CONTENT -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.items, key = { it.name + it.address }) { item ->
                            StayRow(
                                item = item,
                                selected = wizard.stay == item,
                                onSelect = { wizardViewModel.onStaySelect(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 숙소 항목 — 이름 · "{주소} · {설명}" · [선택]. (SPEC §4.9) */
@Composable
private fun StayRow(item: PoiItem, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        onClick = onSelect,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOf(item.address, item.description)
                        .filter { it.isNotEmpty() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "선택됨",
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(onClick = onSelect) { Text("선택") }
            }
        }
    }
}

/** CTA. 숙소를 골랐는지에 따라 문구가 갈린다. (SPEC §4.9) */
@Composable
private fun StayCta(hasStay: Boolean, onClick: () -> Unit) {
    Surface(shadowElevation = 8.dp) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(52.dp),
        ) {
            Text(
                text = if (hasStay) "이 숙소로 동선 추천받기" else "숙소 없이 추천받기",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
