package com.runninggu.app.ui.home

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runninggu.app.domain.today
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState
import com.runninggu.app.ui.common.SectionHeader
import com.runninggu.app.ui.common.CachedNotice
import com.runninggu.app.ui.common.SectionState
import com.runninggu.app.ui.common.cachedAt
import com.runninggu.app.ui.model.FestivalSummary
import com.runninggu.app.ui.model.RaceSummary
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val ScreenPadding = 20.dp

/** 검색 카드 높이와 히어로에 겹치는 양. 겹침은 퀵바가 쓰던 값 그대로다. (목업 .quickbar) */
private val SEARCH_CARD_HEIGHT = 60.dp

/** 검색 칸 힌트. 보이는 글자와 스크린리더가 읽는 글자를 갈라 두지 않는다. */
private const val SEARCH_HINT = "대회·지역 검색"
private val SEARCH_CARD_OVERLAP = 26.dp

/**
 * S1 홈. (SPEC §4.4 / AP-09)
 *
 * 컨셉은 "검색을 먼저 보여주고, 마라톤 검색 → 메인 기능으로".
 * 구성: 히어로(로고 · 축제 사진 배경 · 대표 대회) · 검색 카드 · 마감 임박 대회 · 축제 추천.
 * 달력·지도·코스·관광 퀵바는 하단 탭과 겹쳐 뺐다(2026-09-14 · SPEC §4.4-2).
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit = {},
    onRaceClick: (String) -> Unit = {},
    onStartWizard: (String) -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 공식 페이지를 못 열었을 때 알린다. 마이 화면과 같은 통로다 — 탭 화면이라 Scaffold 를
    // 겹치지 않고 Box 위에 SnackbarHost 를 얹는다.
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    Box(modifier) {
        HomeContent(
            uiState = uiState,
            query = query,
            onQueryChange = viewModel::onSearchQueryChange,
            onSearch = { onSearch(query) },
            onRetryClosingSoon = viewModel::loadClosingSoon,
            onRetryFestivals = viewModel::loadFestivals,
            onRaceClick = onRaceClick,
            onStartWizard = onStartWizard,
            onCannotOpenOfficialPage = viewModel::onCannotOpenOfficialPage,
        )
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
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
    onCannotOpenOfficialPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 히어로(로고·검색·대표 대회)는 다크 몰입 레지스터 한 덩어리다 (목업 .hero).
        // 배경 사진은 축제 영역에서 빌려 온다 — 따로 조회하지 않는다 (SPEC §4.4 히어로 배경).
        item {
            HomeHero(
                race = uiState.featured,
                photos = uiState.heroPhotos,
                onRaceClick = { uiState.featured?.let { onRaceClick(it.id) } },
                onStartWizard = { uiState.featured?.let { onStartWizard(it.id) } },
            )
        }

        // 검색 카드는 흰 카드로 히어로 하단에 26dp 겹친다 (목업 .quickbar 자리 · margin-top:-26px).
        // 예전엔 달력·지도·코스·관광 퀵바가 있던 자리다 — 하단 탭과 겹쳐 뺐다 (SPEC §4.4-2).
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(SEARCH_CARD_HEIGHT - SEARCH_CARD_OVERLAP)
                    // unbounded — 카드가 이 칸보다 커도 잘리지 않고 위로 넘치게 둔다.
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
            ) {
                HomeSearchCard(
                    query = query,
                    onQueryChange = onQueryChange,
                    onSearch = onSearch,
                    modifier = Modifier
                        .offset(y = -SEARCH_CARD_OVERLAP)
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
        ) { festivals ->
            FestivalSection(festivals = festivals, onCannotOpenOfficialPage = onCannotOpenOfficialPage)
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
    content: @Composable (T) -> Unit,
) {
    when (state) {
        SectionState.Loading -> item { LoadingState(message = "불러오는 중…") }
        // **캐시에서 온 0건은 접지 않는다** (#283 리뷰 · 선경님). 접으면 "마지막 성공
        // 결과가 비어 있다" 와 "방금 서버가 0건을 줬다" 가 같은 화면이 된다 — 접수 종료
        // 필터가 다 걸러내면 바로 이 상태라 드물지도 않다. 서버가 준 0건은 지금처럼 접는다.
        is SectionState.Empty -> state.cachedAt?.let { at ->
            item { CachedNotice(cachedAt = at, modifier = Modifier.padding(horizontal = ScreenPadding)) }
        } ?: Unit
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

/**
 * 검색 카드. 히어로 아래에 걸쳐 뜨는 흰 카드다 — 검색 실행 시 S2 캘린더로 이동하고 검색어를
 * 넘긴다. (SPEC §4.4-1)
 *
 * 원래 히어로 맨 위에 반투명 검색 필드가 있고 이 자리엔 달력·지도·코스·관광 퀵바가 있었다.
 * 하단 탭(홈·캘린더·러닝코스·마이)이 같은 곳으로 가는 길을 이미 주고 있어 퀵바를 빼고, 검색을
 * 이 자리로 내렸다(2026-09-14 · 이건모). 히어로에는 사진과 대표 대회만 남아 덜 붐빈다.
 */
@Composable
private fun HomeSearchCard(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SEARCH_CARD_HEIGHT)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                // **플레이스홀더를 편집창의 장식 안에 둔다** (#354 리뷰 · 김민지). 밖에 형제로
                // 두면 힌트가 편집창과 무관한 위치에 놓여, 스크린리더가 둘을 이어 읽을 근거가 없다.
                //
                // 접근성 트리는 이렇게 해도 `EditText` + 힌트 `TextView` 두 노드다 — 기기에서
                // 덤프해 확인했고, **Material3 `OutlinedTextField` 가 내는 구조와 같다**(같은 기기
                // 에서 S2 캘린더 검색창을 덤프해 대조). 즉 앱의 다른 검색창과 같은 기준이 된다.
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = SEARCH_HINT,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
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
 * 축제 섹션의 제목과 몸통. 퀵바 [관광]이 있던 때는 빈 상태에도 이 머리를 남겨 스크롤 목적지를
 * 확보했는데(#102 리뷰), 퀵바가 빠져 지금은 내용이 있을 때만 그린다 — 빈 결과는 접는다(#49).
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
    onCannotOpenOfficialPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FestivalSectionFrame(modifier = modifier) {
        // 사진 카드 캐러셀 — 탭하면 그 카드만 커진다 (#247 · §4.4-4)
        FestivalCarousel(
            festivals = festivals,
            onCannotOpenOfficialPage = onCannotOpenOfficialPage,
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
