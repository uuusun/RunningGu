package com.runninggu.app.ui.course

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.runninggu.app.ui.common.Attributions
import com.runninggu.app.ui.common.ElevationLine
import com.runninggu.app.ui.common.EmptyState
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState
import com.runninggu.app.ui.common.NumberRail
import com.runninggu.app.ui.map.MIN_ROUTE_POINTS
import com.runninggu.app.ui.map.MapScene
import com.runninggu.app.ui.map.RunningGuMap

/**
 * S8 러닝코스. (SPEC §4.11 · AP-12 · AP-03)
 *
 * 지도는 카카오맵 SDK 로 붙어 있다(AP-03). 다만 §4.11-4 의 **앞 갈래(경로 폴리라인)뿐**이라
 * 남은 절반은 [CourseMap] KDoc 에 적어 둔다.
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
        item { CourseMap(state) }

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
                // **번호는 지도 핀과 짝이다.** §4.11-4 가 "리스트 번호 일치" 를 요구하므로
                // 목록 순서 그대로 1부터 매긴다 — 서버가 거리순으로 준 순서다(§4.11-5).
                itemsIndexed(near.items) { index, item ->
                    NearbyRow(
                        item = item,
                        number = index + 1,
                        targetKm = state.targetKm,
                        selected = state.selectedItem == item,
                        onClick = { viewModel.onItemSelect(item) },
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

        MyLocationButton(state = state, viewModel = viewModel)

        // 못 잡아도 화면을 막지 않는다. 아래 검색·프리셋으로 계속 갈 수 있다 (NFR-15)
        state.locationMessage?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
 * [내 위치]. (SPEC §4.11-1 ① · NFR-15)
 *
 * **권한을 물어보는 자리는 여기뿐이다.** 화면에 있어야 시스템 대화상자를 띄울 수 있어서
 * ViewModel 이 못 한다. 허용되면 조회를 맡기고, 거부되면 그 사실만 넘긴다.
 *
 * 정밀·대략 둘 다 요청하되 **하나만 허용해도 진행한다** — 코스 추천은 반경 8km 기준이라
 * 대략 위치로 충분하다.
 */
@Composable
private fun MyLocationButton(state: CourseUiState, viewModel: CourseViewModel) {
    val context = LocalContext.current
    val locating = state.origin == OriginState.Locating

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) {
            viewModel.onUseMyLocation()
        } else {
            viewModel.onLocationPermissionDenied()
        }
    }

    OutlinedButton(
        onClick = {
            val alreadyGranted = LOCATION_PERMISSIONS.any { permission ->
                ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
            // 이미 있으면 다시 묻지 않는다 — 물어봐도 대화상자가 안 뜨고 콜백만 온다
            if (alreadyGranted) {
                viewModel.onUseMyLocation()
            } else {
                permissionLauncher.launch(LOCATION_PERMISSIONS)
            }
        },
        enabled = !locating,
    ) {
        Text(if (locating) "위치를 확인하는 중…" else "내 위치")
    }
}

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

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

/**
 * S8 지도. (SPEC §3-8 · §4.11-4)
 *
 * 동선(S7)과 달리 방문 순서라는 개념이 없어 **경로를 이을 핀을 세우지 않는다.** 카메라는
 * [MapScene] 안의 규칙이 정한다 — 경로가 바뀌면 전체 맞춤이다.
 *
 * ## §4.11-4 의 두 갈래
 *
 * > 선택 항목이 **경로면 왕복 폴리라인**(경로 bounds), **그 외 번호 핀**(잇지 않음,
 * > 리스트 번호 일치) — SPEC §4.11-4
 *
 * 앞 갈래는 #142, 뒤 갈래는 #162 에서 붙었다. 가르는 기준은 **그릴 경로가 있는가**다
 * ([CourseUiState.mapPins]) — 걷기 스팟을 골랐을 때뿐 아니라 **코스가 아예 0건일 때도**
 * 핀을 세운다. 서울 반경 8km 는 코스 0건에 스팟만 나오는 것이 기본이라(§4.11 📌 ·
 * AGENTS 6장) 그러지 않으면 수도권에서 지도가 늘 비어 있다.
 *
 * 핀 번호는 목록 순번 그대로여서(#158 `itemsIndexed`) **중간이 빈다** — `1 경로 ·
 * 2 스팟 · 3 스팟 · 4 경로 · 5 스팟` 이면 지도에는 `2 · 3 · 5` 만 선다. 스팟만 따로
 * 1·2·3 으로 다시 매기면 "리스트 번호 일치" 가 깨지므로 비는 것이 맞다.
 *
 * ## ⚠️ [LazyColumn] 안이라 스크롤로 벗어나면 다시 만들어진다
 *
 * [RunningGuMap] 이 `MapView` 를 `remember` 로 붙드는데, 그 기억은 **이 item 의
 * 컴포지션에 묶인다.** 목록을 내렸다 올리면 SDK 초기화와 카메라가 다시 돈다. 내 주변
 * 목록이 최대 12건이라 화면 밖으로 밀려나는 것은 드문 일이 아니다.
 *
 * 고치는 방법이 여럿이라(고정 영역으로 빼기 등) **실기기에서 보고 정한다** — #104 확인
 * 항목이다.
 *
 * ## 아직 모르는 동안에는 단정하지 않는다
 *
 * 이 카드는 [NearbyState] 바깥에 있어 조회 전·조회 중·실패에도 그려진다. 그때 "따라갈
 * 경로가 없어요" 를 놓으면 아래에서 "이 근처를 찾는 중…" 이 도는 동안 위에서 없다고
 * 단정하는 꼴이 된다. **결과가 실제로 나온 뒤에만**(Content · Empty) 그 문구를 쓴다.
 *
 * 경로가 있는데 [NearbyItem.Route.path] 가 비어 있을 수도 있다 — 매퍼가 폴리라인을
 * 못 풀면 그렇다(#129). 그때는 목록이 `경로` 태그를 달고 있는데 지도만 비는데, 선이
 * 안 되는 좌표열을 그리는 것보다 낫다고 보고 그대로 둔다.
 */
@Composable
private fun CourseMap(state: CourseUiState) {
    val route = state.mappedRoute?.path.orEmpty()
    // 그릴 경로가 없을 때 세우는 걷기 스팟 번호 핀 (§4.11-4)
    val pins = state.mapPins
    // 결과가 나온 뒤에만 "없다" 고 말할 수 있다
    val settled = state.nearby is NearbyState.Content || state.nearby is NearbyState.Empty
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(180.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        when {
            route.size >= MIN_ROUTE_POINTS -> RunningGuMap(
                scene = MapScene(route = route),
                modifier = Modifier.fillMaxSize(),
            )

            // **잇지 않는다.** 흩어진 장소라 방문 순서라는 개념이 없다 (§4.11-4)
            pins.isNotEmpty() -> RunningGuMap(
                scene = MapScene(
                    pins = pins,
                    connectPins = false,
                    activePinId = state.activePinId,
                ),
                modifier = Modifier.fillMaxSize(),
            )

            settled -> MapNotice(
                title = "따라갈 경로가 없어요",
                detail = "걷기 스팟은 아래 목록에서 볼 수 있어요.",
            )

            else -> MapNotice(title = "지도", detail = null)
        }
    }
}

@Composable
private fun MapNotice(title: String, detail: String?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NearbyRow(
    item: NearbyItem,
    /** 목록 순번. 지도 번호 핀과 같은 값이다. (SPEC §4.11-4) */
    number: Int,
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
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            NumberRail(number)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
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

                if (courses.hasNext || courses.moreMessage != null) {
                    item { LoadMoreRow(courses, viewModel) }
                }

                // 목록 하단 출처 한 줄 (SPEC §4.11-b · 결정-44)
                item { Attributions(courses.attributions) }
            }
        }
    }
}

/**
 * 지역별 목록의 [더 보기]. (§4.11-b)
 *
 * 서버가 한 번에 20건씩 주므로 코스가 많은 지역은 이걸 눌러 이어 받는다.
 * 실패해도 이미 받은 목록은 그대로 두고 문구만 붙인다.
 */
@Composable
private fun LoadMoreRow(state: RegionCoursesState.Content, viewModel: CourseViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        state.moreMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (state.hasNext) {
            OutlinedButton(
                onClick = viewModel::loadMoreRegionCourses,
                enabled = state.canLoadMore,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 몇 개가 더 있는지 보이면 누를지 말지 판단할 수 있다
                val rest = state.totalElements - state.courses.size
                Text(
                    if (state.loadingMore) {
                        "불러오는 중…"
                    } else if (rest > 0) {
                        "더 보기 ($rest)"
                    } else {
                        "더 보기"
                    },
                )
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

