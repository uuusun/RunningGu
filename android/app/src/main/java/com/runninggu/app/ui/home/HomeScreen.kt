package com.runninggu.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runninggu.app.R
import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.domain.today
import com.runninggu.app.ui.common.EmptyState
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState
import com.runninggu.app.ui.common.SectionHeader
import com.runninggu.app.ui.common.SectionState
import com.runninggu.app.ui.common.cachedAt
import com.runninggu.app.ui.model.FestivalSummary
import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.ui.model.registrationStatus
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.launch

private val ScreenPadding = 20.dp

/** 퀵바 카드 높이와 히어로에 겹치는 양. (목업 .quickbar) */
private val QUICKBAR_HEIGHT = 72.dp
private val QUICKBAR_OVERLAP = 26.dp

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
    onOpenCourseMap: () -> Unit = {},
    onOpenCourseRegions: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()

    HomeContent(
        uiState = uiState,
        query = query,
        onQueryChange = viewModel::onSearchQueryChange,
        onSearch = { onSearch(query) },
        onRetryClosingSoon = viewModel::loadClosingSoon,
        onRetryFestivals = viewModel::loadFestivals,
        onRaceClick = onRaceClick,
        onStartWizard = onStartWizard,
        onOpenCalendar = onOpenCalendar,
        onOpenCourseMap = onOpenCourseMap,
        onOpenCourseRegions = onOpenCourseRegions,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRetryClosingSoon: () -> Unit,
    onRetryFestivals: () -> Unit,
    onRaceClick: (String) -> Unit,
    onStartWizard: (String) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenCourseMap: () -> Unit,
    onOpenCourseRegions: () -> Unit,
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
        // 히어로(로고·검색·대표 대회)는 다크 몰입 레지스터 한 덩어리다 (목업 .hero).
        item {
            HomeHero(
                race = uiState.featured,
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onRaceClick = { uiState.featured?.let { onRaceClick(it.id) } },
                onStartWizard = { uiState.featured?.let { onStartWizard(it.id) } },
            )
        }

        // 퀵바는 흰 카드로 히어로 하단에 26dp 겹친다 (목업 .quickbar margin-top:-26px).
        // 조회 결과와 무관한 이동 수단이라 영역 상태를 안 본다.
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(QUICKBAR_HEIGHT - QUICKBAR_OVERLAP)
                    // unbounded — 카드가 이 칸보다 커도 잘리지 않고 위로 넘치게 둔다.
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
            ) {
                QuickActionRow(
                    onCalendar = onOpenCalendar,
                    // [지도]와 [코스]는 **같은 S8 의 다른 탭**이다 (SPEC §4.4-2 · 목업
                    // v2 L967-968). 예전에는 둘 다 기본 탭으로 보내서, 다르게 생긴
                    // 버튼 두 개가 같은 화면을 열었다
                    onMap = onOpenCourseMap,
                    onCourse = onOpenCourseRegions,
                    // "관광"은 화면 이동 없이 축제 섹션으로 스크롤한다. (결정-15)
                    onTour = {
                        scope.launch {
                            // 축제는 언제나 마지막 항목이다 — 마감 임박이 접혀도 맞는다
                            val last = listState.layoutInfo.totalItemsCount - 1
                            if (last >= 0) listState.animateScrollToItem(last)
                        }
                    },
                    modifier = Modifier
                        .offset(y = -QUICKBAR_OVERLAP)
                        .padding(horizontal = 22.dp),
                )
            }
        }

        // 두 영역은 서로를 가리지 않는다. 축제가 502 여도 마감 임박은 그대로 보인다
        // (AGENTS 2장-5). 빈 결과는 섹션을 통째로 접는다 — 홈은 탐색 시작점이라
        // "없음" 이 자리를 차지할 이유가 없다(#49 합의).
        section(
            state = uiState.closingSoon,
            errorMessage = "마감 임박 대회를 불러오지 못했어요",
            onRetry = onRetryClosingSoon,
        ) { races ->
            ClosingSoonSection(races = races, onRaceClick = onRaceClick)
        }

        section(
            state = uiState.festivals,
            errorMessage = "축제 정보를 불러오지 못했어요",
            onRetry = onRetryFestivals,
            // 퀵바 [관광]이 이 섹션으로 스크롤한다 — 비어도 자리를 남겨야 겨냥할 곳이 있다
            empty = { FestivalSectionFrame { EmptyState(title = "추천할 축제가 없어요.") } },
        ) { festivals ->
            FestivalSection(festivals = festivals)
        }
    }
}

/**
 * 영역 하나를 그린다. (AGENTS 2장-5 · #49 합의)
 *
 * - **[SectionState.Empty] 는 아무것도 안 그린다** — 섹션 헤더까지 접는다. 홈은 탐색
 *   시작점이라 "없음" 을 자리 잡아 보여줄 이유가 없고, SPEC §4.4 에 홈 섹션 빈 문구
 *   규정도 없다
 * - **[SectionState.Error] 는 그 자리에만** 안내와 재시도를 둔다. 화면 전체를 덮지 않는다
 */
private fun <T> LazyListScope.section(
    state: SectionState<T>,
    errorMessage: String,
    onRetry: () -> Unit,
    /**
     * 비었을 때 자리를 남길 것인가. 기본은 접는다.
     *
     * **퀵액션이 겨냥하는 섹션만 넘긴다**(#102 리뷰). 접히면 스크롤할 자리가 사라져서
     * 버튼이 죽거나 엉뚱한 섹션으로 간다.
     */
    empty: (@Composable () -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        SectionState.Loading -> item { LoadingState(message = "불러오는 중…") }
        is SectionState.Empty -> empty?.let { item { it() } } ?: Unit
        is SectionState.Error -> item {
            // 서버가 준 문구가 있으면 그걸 쓴다. 없을 때만 영역 기본 문구다 (§0-3)
            ErrorState(message = state.message ?: errorMessage, onRetry = onRetry)
        }

        // **캐시로 그린 것이면 그렇다고 말한다** (매핑표 171행 · #276). 영역 단위로 붙이는
        // 이유는 폴백도 영역 단위이기 때문이다 — 마감임박은 캐시에서 오고 축제는 오류일 수
        // 있어서, 화면 위에 한 번 적으면 어느 쪽이 낡은 것인지 알 수 없다.
        is SectionState.Content -> item {
            state.cachedAt?.let {
                CachedNotice(cachedAt = it, modifier = Modifier.padding(horizontal = ScreenPadding))
            }
            content(state.value)
        }
    }
}

@Composable
private fun HomeHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = ScreenPadding, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.app_icon),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
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
    // 목업 .quickbar — 히어로에 걸쳐 뜨는 흰 카드. 그림자로 떠 보이게 한다.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickAction("달력", Icons.Filled.DateRange, onCalendar)
            // 지도는 핀, 코스는 목록이다. 둘 다 핀(`LocationOn`·`Place`)이던 때는
            // 아이콘만 봐서는 어디로 가는지 구분되지 않았다 — 실제로 가는 곳도 같았다.
            // 목업의 `map`·`route` 심볼은 `material-icons-core` 48개에 없다
            QuickAction("지도", Icons.Filled.LocationOn, onMap)
            QuickAction("코스", Icons.AutoMirrored.Filled.List, onCourse)
            QuickAction("관광", Icons.Filled.Face, onTour)
        }
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
            // **제목 자리를 두 줄로 고정한다.** `LazyRow` 의 카드는 내용만큼 자라서,
            // 대회명이 한 줄인 카드와 두 줄인 카드의 높이가 달라진다 — 나란히 놓이는
            // 레일이라 그 차이가 그대로 보인다. 폭(200.dp)만 맞추고 높이를 안 맞춘 탓이다.
            //
            // 높이를 통째로 박지 않는 이유는 **글자 크기 설정**이다. 사용자가 시스템
            // 글꼴을 키우면 고정 높이는 글자를 잘라 먹지만, 줄 수로 잡으면 카드가 함께 큰다.
            Text(
                text = race.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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

/**
 * 축제 섹션의 제목까지. 내용과 빈 자리가 **같은 머리를 쓰도록** 갈라 두었다 (#102 리뷰).
 *
 * 비었을 때도 제목이 남아야 퀵바 [관광]이 데려다 놓은 자리가 무엇인지 알 수 있다.
 */
@Composable
private fun FestivalSectionFrame(
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        SectionHeader(
            title = "축제·지역 관광 추천",
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(12.dp))
        body()
    }
}

@Composable
private fun FestivalSection(
    festivals: List<FestivalSummary>,
    modifier: Modifier = Modifier,
) {
    FestivalSectionFrame(modifier = modifier) {
        // 사진 카드 캐러셀 — 탭하면 그 카드만 커진다 (#247 · §4.4-4)
        FestivalCarousel(
            festivals = festivals,
            contentPadding = PaddingValues(horizontal = ScreenPadding),
        )
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


/** 지역을 제공하지 않은 축제는 구분점 없이 기간만 표시한다. (API 명세 §4-1) */
internal fun festivalPeriodAndRegion(period: String, region: String): String =
    if (region.isBlank()) period else "$period · $region"

// ── 날짜 표시 도우미 ──
// TODO(AP-04): 도메인 포팅(dates.kt·KST 규칙)이 들어오면 그쪽으로 옮긴다.

private fun LocalDate.daysFromToday(): Long =
    ChronoUnit.DAYS.between(today(), this)

private fun LocalDate.toKoreanDate(): String {
    val dow = dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
    return "%02d.%02d %s".format(monthValue, dayOfMonth, dow)
}

private fun LocalDate.toShortDate(): String = "%02d.%02d".format(monthValue, dayOfMonth)
