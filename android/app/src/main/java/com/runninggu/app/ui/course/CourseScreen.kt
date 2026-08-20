package com.runninggu.app.ui.course

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runninggu.app.data.model.CourseSummary
import com.runninggu.app.data.model.CourseTargetKm
import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.ui.common.ElevationLine
import com.runninggu.app.ui.common.EmptyState
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState

/**
 * S8 러닝코스. (SPEC §4.11 · AP-12)
 *
 * 지도는 AP-03(카카오맵 SDK)에서 붙는다 — 지금은 자리만 비워 둔다.
 */
@Composable
fun CourseScreen(
    viewModel: CourseViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "러닝·산책 코스",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        TabRow(selectedTabIndex = state.tab.ordinal) {
            CourseUiState.Tab.entries.forEach { tab ->
                Tab(
                    selected = state.tab == tab,
                    onClick = { viewModel.onTabChange(tab) },
                    text = { Text(tab.label) },
                )
            }
        }

        when (state.tab) {
            CourseUiState.Tab.NEARBY -> NearbyTab(state, viewModel)
            CourseUiState.Tab.BY_REGION -> RegionTab(state, viewModel)
        }
    }
}

// ── 내 주변 ────────────────────────────────────────────────

@Composable
private fun NearbyTab(state: CourseUiState, viewModel: CourseViewModel) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item { OriginRow(state, viewModel) }
        item { TargetSlider(state, viewModel) }
        item { MapPlaceholder() }

        when (val near = state.nearby) {
            NearbyState.Idle -> item {
                EmptyState(title = "출발지를 정해주세요.", description = "내 위치를 켜거나 아래에서 고르세요.")
            }

            NearbyState.Loading -> item { LoadingState(message = "이 근처를 찾는 중…") }

            NearbyState.Empty -> item {
                EmptyState(
                    title = "이 근처엔 걸을 곳을 못 찾았어요.",
                    description = "출발지를 바꾸거나 목표 거리를 조정해 보세요.",
                )
            }

            is NearbyState.Error -> item {
                ErrorState(message = near.message, onRetry = viewModel::refreshNearby)
            }

            is NearbyState.Content -> {
                near.degradedMessage?.let { message ->
                    item { NoticeRow(message) }
                }
                item {
                    Text(
                        text = "이 근처에서 뛸 만한 곳",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                // 서버가 거리순으로 섞어 준 순서 그대로 그린다 — 앱은 재정렬하지 않는다(결정-27)
                items(near.items) { item ->
                    NearbyRow(
                        item = item,
                        targetKm = state.targetKm,
                        selected = (item as? NearbyItem.Route)?.routeId == state.selectedRouteId,
                        onClick = { viewModel.onItemSelect((item as? NearbyItem.Route)?.routeId) },
                    )
                }
                item { ActionRow(hasNoRoute = near.hasNoRoute) }
                item { Attributions(near.attributions) }
            }
        }
    }
}

@Composable
private fun OriginRow(state: CourseUiState, viewModel: CourseViewModel) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        val label = when (val origin = state.origin) {
            OriginState.Undecided -> "출발지를 정해주세요."
            OriginState.Locating -> "위치를 확인하는 중…"
            is OriginState.Fixed -> origin.name
        }
        Text(text = label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)

        Spacer(Modifier.height(8.dp))

        OriginSearchField(state.originSearch, viewModel)

        Spacer(Modifier.height(8.dp))

        // 위치 권한을 거부해도 여기로 고를 수 있다 (NFR-15)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ORIGIN_PRESETS.forEach { preset ->
                FilterChip(
                    selected = (state.origin as? OriginState.Fixed)?.name == preset.name,
                    onClick = { viewModel.onOriginChange(preset) },
                    label = { Text(preset.name) },
                )
            }
        }
    }
}

/**
 * 출발지 검색. (SPEC §4.11-1 ② · API 명세 §4-4)
 *
 * 서버가 첫 결과 하나만 주므로 후보 목록을 그리지 않는다 — 찾으면 곧바로 출발지가 된다.
 */
@Composable
private fun OriginSearchField(state: OriginSearchState, viewModel: CourseViewModel) {
    Column {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onOriginQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.searching,
            placeholder = { Text("장소나 주소로 찾기") },
            isError = state.message != null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.onOriginSearch() }),
            trailingIcon = {
                IconButton(onClick = viewModel::onOriginSearch, enabled = state.canSubmit) {
                    Icon(Icons.Default.Search, contentDescription = "출발지 검색")
                }
            },
        )

        if (state.searching) {
            Text(
                text = "찾는 중…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TargetSlider(state: CourseUiState, viewModel: CourseViewModel) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "목표 거리 ${formatKm(state.targetKm)}km",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = state.targetKm.toFloat(),
            onValueChange = { viewModel.onTargetKmChange(it.toDouble()) },
            onValueChangeFinished = viewModel::onTargetKmCommit,
            valueRange = CourseTargetKm.MIN.toFloat()..CourseTargetKm.MAX.toFloat(),
            // 1~21km 를 0.5 단위로 — 양 끝을 뺀 사이 눈금 수다
            steps = TARGET_SLIDER_STEPS,
        )
    }
}

/** TODO(AP-03): 카카오맵 SDK 가 붙으면 폴리라인·번호 핀을 그린다. (SPEC §4.11-4) */
@Composable
private fun MapPlaceholder() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(140.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("지도", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "AP-03 카카오맵 연동 예정",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NearbyRow(
    item: NearbyItem,
    targetKm: Double,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item is NearbyItem.Route) {
                    // 원천 이름 대신 "따라갈 경로가 있는가" 만 표시한다 (§4.11-5)
                    Text(
                        text = "경로",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = nearbySubtitle(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (item is NearbyItem.Route) {
                if (item.elevationProfileM.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    ElevationLine(
                        seed = item.routeId.hashCode(),
                        closed = false,
                        profile = item.elevationProfileM.map { it.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp),
                    )
                }
                if (item.shortfall) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "이 근처 경로가 짧아 목표(${formatKm(targetKm)}km)보다 짧게 짜였어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(hasNoRoute: Boolean) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { /* TODO(AP-14 4단계): POST /me/courses */ }) { Text("저장") }
            OutlinedButton(onClick = { /* TODO(AP-22 · P1): GPS 기록 */ }) { Text("뛰기") }
        }
        if (hasNoRoute) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "이 근처엔 따라갈 경로가 없어요. 자유롭게 뛰어도 기록은 그대로 남습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 지역별 ────────────────────────────────────────────────

@Composable
private fun RegionTab(state: CourseUiState, viewModel: CourseViewModel) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item { RegionChips(state, viewModel) }

        when (val courses = state.regionCourses) {
            RegionCoursesState.Loading -> item { LoadingState(message = "코스를 불러오는 중…") }
            RegionCoursesState.Empty -> item { EmptyState(title = "이 지역엔 코스가 없어요.") }
            is RegionCoursesState.Error -> item {
                ErrorState(message = courses.message, onRetry = viewModel::loadRegionCourses)
            }

            is RegionCoursesState.Content -> {
                item {
                    Text(
                        // 전체 건수다 — 지금 보이는 개수가 아니다 (§4.11-b)
                        text = "${state.selectedRegion ?: "전국"} 코스 ${courses.totalElements}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(courses.courses) { CourseRow(it) }

                // 목록 하단 출처 한 줄 (SPEC §4.11-b · 결정-44)
                item { Attributions(courses.attributions) }
            }
        }
    }
}

@Composable
private fun RegionChips(state: CourseUiState, viewModel: CourseViewModel) {
    when (val regions = state.regions) {
        RegionsState.Loading -> LoadingState(message = "지역을 불러오는 중…")

        // 칩을 못 불러와도 목록은 전국 기준으로 보인다
        is RegionsState.Error -> NoticeRow(regions.message)

        is RegionsState.Content -> Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 코스 수 내림차순은 서버가 정한다. 재탭하면 해제된다 (§4.11-b)
            regions.regions.forEach { region ->
                FilterChip(
                    selected = state.selectedRegion == region.region,
                    onClick = { viewModel.onRegionToggle(region.region) },
                    label = { Text("${region.region} ${region.count}") },
                )
            }
        }
    }
}

@Composable
private fun CourseRow(course: CourseSummary) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = course.courseName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = courseSubtitle(course),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 공통 ──────────────────────────────────────────────────

/** 비차단 안내. 목록은 그대로 두고 위에 한 줄만 얹는다. (§4.11-7) */
@Composable
private fun NoticeRow(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** 출처는 **순서·문구를 바꾸지 않고** 그대로 낸다 — 공공누리·ODbL 의무다. (§4.11-5) */
@Composable
private fun Attributions(attributions: List<String>) {
    if (attributions.isEmpty()) return
    Text(
        text = "출처 · " + attributions.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
