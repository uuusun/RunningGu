package com.runninggu.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runninggu.app.ui.common.EmptyState
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState
import com.runninggu.app.ui.common.SectionHeader
import com.runninggu.app.ui.model.FestivalSummary
import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.ui.model.RegistrationStatus
import com.runninggu.app.ui.model.registrationStatus
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val ScreenPadding = 20.dp

/**
 * S1 홈. (SPEC §4.4 / AP-09)
 *
 * 컨셉은 "검색을 먼저 보여주고, 마라톤 검색 → 메인 기능으로".
 * 구성: 헤더 · 검색 바 · 히어로 대회 · 기능 아이콘 행 · 마감 임박 대회 · 축제 추천.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit = {},
    onRaceClick: (String) -> Unit = {},
    onStartWizard: (String) -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenCourses: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()

    HomeContent(
        uiState = uiState,
        query = query,
        onQueryChange = viewModel::onSearchQueryChange,
        onSearch = { onSearch(query) },
        onRetry = viewModel::load,
        onRaceClick = onRaceClick,
        onStartWizard = onStartWizard,
        onOpenCalendar = onOpenCalendar,
        onOpenCourses = onOpenCourses,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRetry: () -> Unit,
    onRaceClick: (String) -> Unit,
    onStartWizard: (String) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenCourses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { HomeHeader() }

        item {
            SearchBar(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }

        when (uiState) {
            HomeUiState.Loading -> item { LoadingState(message = "불러오는 중…") }

            is HomeUiState.Error -> item {
                ErrorState(message = uiState.message, onRetry = onRetry)
            }

            is HomeUiState.Content -> {
                if (uiState.isEmpty) {
                    item {
                        EmptyState(
                            title = "보여드릴 대회가 없어요.",
                            description = "잠시 후 다시 확인해 주세요.",
                        )
                    }
                } else {
                    uiState.featured?.let { race ->
                        item {
                            FeaturedRaceCard(
                                race = race,
                                onClick = { onRaceClick(race.id) },
                                onStartWizard = { onStartWizard(race.id) },
                                modifier = Modifier.padding(horizontal = ScreenPadding),
                            )
                        }
                    }

                    item {
                        QuickActionRow(
                            onCalendar = onOpenCalendar,
                            onMap = onOpenCourses,
                            onCourse = onOpenCourses,
                            // "관광"은 화면 이동 없이 축제 섹션으로 스크롤한다. (결정-15)
                            onTour = {
                                scope.launch {
                                    listState.animateScrollToItem(uiState.festivalSectionIndex)
                                }
                            },
                            modifier = Modifier.padding(horizontal = ScreenPadding),
                        )
                    }

                    item {
                        ClosingSoonSection(
                            races = uiState.closingSoon,
                            onRaceClick = onRaceClick,
                        )
                    }

                    item {
                        FestivalSection(festivals = uiState.festivals)
                    }
                }
            }
        }
    }
}

/**
 * 축제 섹션의 LazyColumn 내 위치 — 헤더·검색·(히어로)·퀵바·마감임박 다음.
 * 히어로는 featured가 없으면 빠지므로 그만큼 당겨진다. 섹션을 추가하면 같이 조정한다.
 */
private val HomeUiState.Content.festivalSectionIndex: Int
    get() = if (featured != null) 5 else 4

@Composable
private fun HomeHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = ScreenPadding, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = "런닝구",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

/** 검색 실행 시 S2 캘린더로 이동하고 검색어를 넘긴다. (SPEC §4.4-1) */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("대회·지역 검색") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@Composable
private fun FeaturedRaceCard(
    race: RaceSummary,
    onClick: () -> Unit,
    onStartWizard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "D-${race.date.daysFromToday()}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(
                        text = race.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${race.date.toKoreanDate()} ${race.startTime} · ${race.venue}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(13.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(race = race)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = race.source,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onStartWizard,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("이 대회로 동선 만들기", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatusChip(race: RaceSummary, modifier: Modifier = Modifier) {
    val status = race.registrationStatus()
    val isOpen = status == RegistrationStatus.OPEN
    val label = if (isOpen && race.regEnd != null) {
        "접수중 · ~${race.regEnd.toShortDate()}"
    } else {
        status.label
    }
    val container = if (isOpen) {
        // 접수중은 라임 (목업 .chip-open). S2 카드와 같은 색을 쓴다.
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .background(container, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** 달력(→S2) · 지도(→S8) · 코스(→S8) · 관광(축제 섹션으로 스크롤). (SPEC §4.4-2) */
@Composable
private fun QuickActionRow(
    onCalendar: () -> Unit,
    onMap: () -> Unit,
    onCourse: () -> Unit,
    onTour: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        QuickAction("달력", Icons.Filled.DateRange, onCalendar)
        QuickAction("지도", Icons.Filled.LocationOn, onMap)
        QuickAction("코스", Icons.Filled.Place, onCourse)
        QuickAction("관광", Icons.Filled.Face, onTour)
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(25.dp))
        Spacer(Modifier.height(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ClosingSoonSection(
    races: List<RaceSummary>,
    onRaceClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(
            title = "마감 임박 대회",
            trailing = "접수 마감 순",
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(12.dp))
        if (races.isEmpty()) {
            EmptyState(title = "마감이 임박한 대회가 없어요.")
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(races, key = { it.id }) { race ->
                    ClosingSoonCard(race = race, onClick = { onRaceClick(race.id) })
                }
            }
        }
    }
}

@Composable
private fun ClosingSoonCard(
    race: RaceSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deadline = race.regEnd?.daysFromToday()
    Card(
        onClick = onClick,
        modifier = modifier.width(200.dp),
        shape = RoundedCornerShape(16.dp),
        // 목업 .railcard — 흰 바탕 + 옅은 테두리 + 얕은 그림자.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (deadline != null) {
                // 마감 10일 이내는 강조색으로 구분한다.
                val urgent = deadline <= 10
                Text(
                    text = "마감 D-$deadline",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (urgent) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier
                        .background(
                            if (urgent) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            CircleShape,
                        )
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(11.dp))
            Text(
                text = race.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${race.region} · ${race.date.toKoreanDate()} ${race.startTime}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FestivalSection(
    festivals: List<FestivalSummary>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(
            title = "축제·지역 관광 추천",
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(12.dp))
        if (festivals.isEmpty()) {
            EmptyState(title = "추천할 축제가 없어요.")
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(festivals, key = { it.id }) { festival ->
                    FestivalCard(festival = festival)
                }
            }
            Spacer(Modifier.height(10.dp))
            // 출처 표기는 한국관광공사 고정. (NFR-7)
            Text(
                text = "출처 · 한국관광공사",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }
    }
}

@Composable
private fun FestivalCard(
    festival: FestivalSummary,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.width(200.dp),
        shape = RoundedCornerShape(16.dp),
        // 목업 .railcard — 흰 바탕 + 옅은 테두리 + 얕은 그림자.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (festival.isOngoing) {
                Text(
                    text = "진행 중",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Spacer(Modifier.height(9.dp))
            }
            Text(
                text = festival.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${festival.period} · ${festival.region}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 날짜 표시 도우미 ──
// TODO(AP-04): 도메인 포팅(dates.kt·KST 규칙)이 들어오면 그쪽으로 옮긴다.

private fun LocalDate.daysFromToday(): Long =
    ChronoUnit.DAYS.between(LocalDate.now(), this)

private fun LocalDate.toKoreanDate(): String {
    val dow = dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
    return "%02d.%02d %s".format(monthValue, dayOfMonth, dow)
}

private fun LocalDate.toShortDate(): String = "%02d.%02d".format(monthValue, dayOfMonth)
