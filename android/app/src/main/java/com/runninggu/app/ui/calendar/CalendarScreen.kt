package com.runninggu.app.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runninggu.app.ui.common.EmptyState
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState
import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.ui.model.registrationStatus
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val ScreenPadding = 20.dp

/**
 * S2 캘린더. (SPEC §4.5 / AP-10)
 *
 * 리스트·캘린더 뷰 토글 · 즉시 검색 · 필터 모달 · 대회 카드(찜 포함).
 * [initialQuery]는 홈에서 검색 실행 시 넘어온 검색어다.
 */
@Composable
fun CalendarScreen(
    initialQuery: String = "",
    modifier: Modifier = Modifier,
    onRaceClick: (String) -> Unit = {},
    onLoginRequest: () -> Unit = {},
    viewModel: CalendarViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val loginRequired by viewModel.loginRequired.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(initialQuery) {
        viewModel.applyInitialQuery(initialQuery)
    }

    // 찜 토글 스낵바. Duration은 Short. (SPEC §3-4)
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = text, duration = SnackbarDuration.Short)
        viewModel.onMessageShown()
    }

    // 게스트가 하트를 누르면 로그인으로 보낸다. 찜을 예약해 두지 않는다 (결정-4 · D-27).
    LaunchedEffect(loginRequired) {
        if (loginRequired) {
            onLoginRequest()
            viewModel.onLoginRequiredShown()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        CalendarContent(
            uiState = uiState,
            onQueryChange = viewModel::onQueryChange,
            onViewModeChange = viewModel::onViewModeChange,
            onFilterApply = viewModel::onFilterApply,
            onFilterChipRemove = viewModel::onFilterChipRemove,
            onResetAll = viewModel::onResetAll,
            onMonthChange = viewModel::onMonthChange,
            onDateSelect = viewModel::onDateSelect,
            onFavoriteToggle = viewModel::onFavoriteToggle,
            onRetry = viewModel::load,
            onRaceClick = onRaceClick,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun CalendarContent(
    uiState: CalendarUiState,
    onQueryChange: (String) -> Unit,
    onViewModeChange: (CalendarViewMode) -> Unit,
    onFilterApply: (RaceFilter) -> Unit,
    onFilterChipRemove: (ActiveFilterChip) -> Unit,
    onResetAll: () -> Unit,
    onMonthChange: (Long) -> Unit,
    onDateSelect: (LocalDate) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onRetry: () -> Unit,
    onRaceClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filterSheetVisible by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        ViewModeToggle(
            current = uiState.viewMode,
            onChange = onViewModeChange,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )

        SearchField(
            query = uiState.query,
            onQueryChange = onQueryChange,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(10.dp))
        FilterRow(
            filter = uiState.filter,
            onOpenSheet = { filterSheetVisible = true },
            onChipRemove = onFilterChipRemove,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(8.dp))
        when (uiState.phase) {
            CalendarUiState.Phase.LOADING -> LoadingState(message = "불러오는 중…")

            CalendarUiState.Phase.ERROR -> ErrorState(
                message = uiState.errorMessage ?: "대회를 못 불러왔어요. 다시 시도해 주세요",
                onRetry = onRetry,
            )

            CalendarUiState.Phase.LOADED -> RaceList(
                uiState = uiState,
                onMonthChange = onMonthChange,
                onDateSelect = onDateSelect,
                onRaceClick = onRaceClick,
                onFavoriteToggle = onFavoriteToggle,
                onResetAll = onResetAll,
            )
        }
    }

    if (filterSheetVisible) {
        RaceFilterSheet(
            initial = uiState.filter,
            onDismiss = { filterSheetVisible = false },
            onApply = {
                onFilterApply(it)
                filterSheetVisible = false
            },
        )
    }
}

@Composable
private fun RaceList(
    uiState: CalendarUiState,
    onMonthChange: (Long) -> Unit,
    onDateSelect: (LocalDate) -> Unit,
    onRaceClick: (String) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val races = uiState.listedRaces
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (uiState.viewMode == CalendarViewMode.CALENDAR) {
            item {
                MonthCalendar(
                    month = uiState.currentMonth,
                    racesByDate = uiState.racesByDate,
                    selectedDate = uiState.selectedDate,
                    onMonthChange = onMonthChange,
                    onDateSelect = onDateSelect,
                )
            }
        }

        item { ListHeader(uiState = uiState) }

        if (races.isEmpty()) {
            item { EmptyBody(uiState = uiState, onResetAll = onResetAll) }
        } else {
            items(races, key = { it.id }) { race ->
                RaceCard(
                    race = race,
                    isFavorite = uiState.isFavorite(race.id),
                    // featured는 '목록 첫 항목이면서 접수중'일 때만. (SPEC §4.5)
                    featured = race.id == races.first().id &&
                        race.registrationStatus() == RegistrationStatus.OPEN,
                    onClick = { onRaceClick(race.id) },
                    onFavoriteToggle = { onFavoriteToggle(race.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewModeToggle(
    current: CalendarViewMode,
    onChange: (CalendarViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        CalendarViewMode.LIST to "리스트",
        CalendarViewMode.CALENDAR to "캘린더",
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = current == mode,
                onClick = { onChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label)
            }
        }
    }
}

/** 입력 즉시 필터. (SPEC §4.5) */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("대회·지역 검색") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = "검색어 지우기",
                    modifier = Modifier
                        .clickable { onQueryChange("") }
                        .size(20.dp),
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
    )
}

/** [필터] 버튼 + 적용 중 조건 칩(✕로 개별 해제). (SPEC §4.5) */
@Composable
private fun FilterRow(
    filter: RaceFilter,
    onOpenSheet: () -> Unit,
    onChipRemove: (ActiveFilterChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onOpenSheet) {
            Icon(Icons.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("필터")
        }
        filter.activeChips().forEach { chip ->
            AssistChip(
                onClick = { onChipRemove(chip) },
                label = { Text(chip.label) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = "${chip.label} 해제",
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun ListHeader(uiState: CalendarUiState, modifier: Modifier = Modifier) {
    val label = if (uiState.viewMode == CalendarViewMode.CALENDAR) {
        val day = uiState.selectedDate
        if (day != null) {
            val dow = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
            "${day.monthValue}.${day.dayOfMonth} ($dow) 대회"
        } else {
            "${uiState.currentMonth.monthValue}월 대회"
        }
    } else {
        "대회"
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = uiState.listedRaces.size.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyBody(
    uiState: CalendarUiState,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.viewMode == CalendarViewMode.CALENDAR) {
        val unit = if (uiState.selectedDate != null) "날" else "달"
        EmptyState(title = "이 ${unit}엔 대회가 없어요.", modifier = modifier)
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(
                title = "조건에 맞는 대회가 없어요.",
                description = "필터를 바꿔보세요.",
            )
            OutlinedButton(onClick = onResetAll) { Text("필터 초기화") }
        }
    }
}
