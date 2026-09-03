package com.runninggu.app.ui.racedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.ui.common.BottomActionBar
import com.runninggu.app.ui.common.EmptyState
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState
import com.runninggu.app.ui.common.SectionHeader
import com.runninggu.app.ui.common.openInCustomTab
import com.runninggu.app.ui.common.openableWebUrl
import com.runninggu.app.ui.model.NearbyFestival
import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.ui.model.dDayLabel
import com.runninggu.app.ui.model.isDimmed
import com.runninggu.app.ui.model.nearbyFestivalPeriod
import com.runninggu.app.ui.model.registrationStatus
import com.runninggu.app.ui.theme.Ink5
import com.runninggu.app.ui.theme.Orange
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd")

/** "08.31 월" — 카드·히어로 공통 표기. (SPEC §4.6) */
private fun LocalDate.withWeekday(): String =
    "${format(MONTH_DAY)} ${dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}"

/**
 * S3 대회 상세. (SPEC §4.6 · AP-11)
 *
 * 탭바는 [com.runninggu.app.ui.RunningGuApp]이 최상위 화면에서만 그리므로
 * 이 화면에서는 자동으로 숨는다 (SPEC §2.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceDetailScreen(
    raceId: String,
    onBack: () -> Unit,
    onStartWizard: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLoginRequest: () -> Unit,
    viewModel: RaceDetailViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val loginRequired by viewModel.loginRequired.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(raceId) { viewModel.start(raceId) }

    // 찜 토글 스낵바. Duration Short ≈ 목업 2.2초 (SPEC §3-4)
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    // 게스트가 하트를 누르면 로그인으로 보낸다 (결정-4 · D-27).
    LaunchedEffect(loginRequired) {
        if (loginRequired) {
            onLoginRequest()
            viewModel.onLoginRequiredShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                // 상단 인셋은 앱 셸이 넘기지 않으므로 TopAppBar 기본 동작(statusBars)에 맡긴다.
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    // **안드로이드 공유 시트를 쓴다** (SPEC §4.6 · A4 개정).
                    // 원래 "표시 전용, 실공유는 AP-17(P1)" 이었는데, 카카오 공유 SDK 를
                    // 기다릴 이유가 없었다 — `ACTION_SEND` 는 새 의존성이 0개고 시트에
                    // 카톡·문자·링크 복사가 다 뜬다. 카톡 전용 카드(썸네일·버튼)는
                    // 그대로 AP-17 에 남는다.
                    //
                    // 대회를 아직 못 불러왔으면 보낼 것이 없다 — 하단 CTA 와 같은 기준으로
                    // 비활성이다(§4.6). 누를 수는 있는데 아무 일이 없는 편이 더 나쁘다.
                    IconButton(
                        onClick = { state.race?.let(context::shareRace) },
                        enabled = state.race != null,
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "공유")
                    }
                    IconButton(onClick = viewModel::onFavoriteToggle) {
                        Icon(
                            imageVector = if (state.isFavorite) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Filled.FavoriteBorder
                            },
                            contentDescription = if (state.isFavorite) "찜 해제" else "찜하기",
                            // 색은 S2 카드(RaceCard)와 맞춘다 — 같은 찜인데 화면마다 색이 다르면 안 된다.
                            tint = if (state.isFavorite) Orange else Ink5,
                        )
                    }
                },
            )
        },
        bottomBar = {
            // 하단 고정 CTA. 대회를 못 불러왔으면 동선을 짤 수 없으므로 숨긴다. (SPEC §4.6)
            //
            // 비활성·서버 미보유 대회에서는 **숨기지 않고 비활성으로 둔다.** 버튼이 통째로
            // 사라지면 "원래 없는 화면" 처럼 보여서, 왜 못 만드는지 알 수 없다 (결정-46).
            if (state.phase == RaceDetailUiState.Phase.LOADED) {
                StartWizardBar(
                    onClick = { onStartWizard(raceId) },
                    enabled = state.canStartWizard,
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when (state.phase) {
                RaceDetailUiState.Phase.LOADING ->
                    LoadingState("불러오는 중…")

                RaceDetailUiState.Phase.NOT_FOUND ->
                    EmptyState(
                        title = "대회 정보를 찾을 수 없어요.",
                        description = "삭제됐거나 주소가 잘못됐을 수 있어요.",
                    )

                RaceDetailUiState.Phase.ERROR ->
                    ErrorState(
                        message = state.errorMessage ?: "대회 정보를 못 불러왔어요.",
                        onRetry = viewModel::load,
                    )

                RaceDetailUiState.Phase.LOADED ->
                    state.race?.let { race ->
                        RaceDetailContent(
                            race = race,
                            festivalPhase = state.festivalPhase,
                            festivals = state.festivals,
                            showFestivals = state.showFestivalSection,
                            onRetryFestivals = viewModel::loadFestivals,
                            onCannotOpenOfficialPage = viewModel::onCannotOpenOfficialPage,
                        )
                    }
            }
        }
    }
}

@Composable
private fun RaceDetailContent(
    race: RaceSummary,
    festivalPhase: RaceDetailUiState.FestivalPhase,
    festivals: List<NearbyFestival>,
    showFestivals: Boolean,
    onRetryFestivals: () -> Unit,
    onCannotOpenOfficialPage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        // 안내는 흐리게 하지 않는다 — 왜 흐린지 설명하는 줄이라 이것까지 흐려지면 안 읽힌다.
        if (!race.active) {
            InactiveNotice()
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.alpha(if (race.isDimmed()) DIMMED_ALPHA else 1f)) {
            RaceHero(race)
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            RaceInfo(race, onCannotOpen = onCannotOpenOfficialPage)
        }
        if (showFestivals) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            NearbyFestivalSection(
                phase = festivalPhase,
                festivals = festivals,
                onRetry = onRetryFestivals,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 원천에서 사라진 대회 안내. (SPEC §4.6 · §4.13 · 결정-46)
 *
 * 대회가 취소됐다고 단정하지 않는다 — 우리가 소식을 더 받지 못할 뿐이라, 사용자가
 * 주최 측 공식 페이지를 확인할 여지를 남긴다. 공식 페이지 링크는 아래 정보에 그대로 있다.
 */
@Composable
private fun InactiveNotice() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "정보 제공 종료",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "이 대회 소식을 더 받아오지 못해요. 아래 정보는 마지막으로 확인한 내용이에요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 지난·비활성 대회 흐림 정도. `RaceCard` 와 같은 값을 쓴다. (SPEC §4.13) */
private const val DIMMED_ALPHA = 0.45f

/** 히어로 — 지역·대회명·D-day·일시·장소·접수 배지. (SPEC §4.6) */
@Composable
private fun RaceHero(race: RaceSummary) {
    val status = race.registrationStatus()
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            text = "${race.region} · 마라톤",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = race.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = race.dDayLabel(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = race.date.withWeekday(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "출발 ${race.startTime} · ${race.venue}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 원천이 사라진 대회의 접수 상태는 우리가 말할 수 없다. "접수중" 을 그대로 두면
        // 바로 위 "정보 제공 종료" 안내와 정면으로 어긋난다 (결정-46).
        if (race.active) {
            Spacer(Modifier.height(12.dp))
            RegistrationBadge(race, status)
        }
    }
}

/** 접수 배지 — 접수중은 강조하고 마감일을 함께 보여준다. (SPEC §4.6) */
@Composable
private fun RegistrationBadge(race: RaceSummary, status: RegistrationStatus) {
    val label = when (status) {
        RegistrationStatus.OPEN ->
            race.regEnd?.let { "${status.label} · ~${it.format(MONTH_DAY)}" } ?: status.label

        RegistrationStatus.BEFORE ->
            race.regStart?.let { "${status.label} · ${it.format(MONTH_DAY)} 오픈" } ?: status.label

        else -> status.label
    }
    val highlighted = status == RegistrationStatus.OPEN
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            disabledLabelColor = if (highlighted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    )
}

/** 정보 — 종목·접수 기간·주최·출처 + 공식 페이지. (SPEC §4.6) */
@Composable
private fun RaceInfo(race: RaceSummary, onCannotOpen: () -> Unit) {
    val context = LocalContext.current
    Column {
        InfoRow("종목", race.eventTypes.joinToString(" · "))
        InfoRow("접수 기간", registrationPeriod(race))
        InfoRow("주최", race.organizer ?: "정보 없음")
        Spacer(Modifier.height(12.dp))
        Text(
            text = buildString {
                append("출처 ${race.source}")
                race.checked?.let { append(" · 확인 ${it.format(MONTH_DAY)}") }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 열 수 없는 주소면 버튼 자체를 내지 않는다. 눌렀는데 아무 일도 안 나는 것보다 낫다
        openableWebUrl(race.officialUrl)?.let { url ->
            TextButton(
                onClick = { if (!openInCustomTab(context, url)) onCannotOpen() },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("공식 페이지 ↗")
            }
        }
    }
}

/** "07.14 ~ 08.17" · 한쪽만 있으면 그쪽만 · 둘 다 없으면 "정보 없음". */
private fun registrationPeriod(race: RaceSummary): String {
    val start = race.regStart?.format(MONTH_DAY)
    val end = race.regEnd?.format(MONTH_DAY)
    return when {
        start != null && end != null -> "$start ~ $end"
        start != null -> "$start ~"
        end != null -> "~ $end"
        else -> "정보 없음"
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 인근 축제 (M3). (SPEC §4.6 · API 명세 §3-5)
 *
 * 대회 본문과 독립된 상태를 쓰므로, 여기서 오류가 나도 위쪽 대회 정보는 그대로 남는다.
 */
@Composable
private fun NearbyFestivalSection(
    phase: RaceDetailUiState.FestivalPhase,
    festivals: List<NearbyFestival>,
    onRetry: () -> Unit,
) {
    Column {
        SectionHeader(title = "대회 인근 축제", trailing = "한국관광공사")
        when {
            phase == RaceDetailUiState.FestivalPhase.LOADING ->
                LoadingState("축제를 찾는 중…")

            phase == RaceDetailUiState.FestivalPhase.ERROR ->
                ErrorState(message = "축제를 못 불러왔어요.", onRetry = onRetry)

            // 좌표가 없어 서버가 반경을 못 잰다. **[다시 시도]를 붙이지 않는다** —
            // 다시 눌러도 좌표는 생기지 않는다. 다만 **빈 상태로 그리지도 않는다** —
            // 명세가 "빈 상태가 아니라 재시도 버튼이 없는 별도 오류 상태" 로 못 박는다
            // (§3-5). 아래 빈 상태와 같아 보이면 "축제가 없는 대회" 와 구분이 안 된다.
            phase == RaceDetailUiState.FestivalPhase.LOCATION_UNAVAILABLE ->
                ErrorState(message = "인근 축제를 확인할 수 없어요.")

            festivals.isEmpty() ->
                EmptyState(
                    title = "대회 기간에 열리는 인근 축제가 없어요.",
                    description = "대회 전후 여행 동선은 그대로 짜드릴 수 있어요.",
                )

            else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(4.dp))
                festivals.forEach { FestivalRow(it) }
            }
        }
    }
}

@Composable
private fun FestivalRow(festival: NearbyFestival) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // TODO(AP-19): imageUrl을 Coil로 로딩한다. 지금은 자리만 잡아둔다.
        Surface(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(Modifier.background(Color.Transparent))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = festival.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = nearbyFestivalPeriod(festival.startDate, festival.endDate) +
                    " · 대회장 ${"%.1f".format(festival.distanceKm)}km",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 하단 고정 CTA — "이 대회로 동선 만들기" → S4. (SPEC §4.6) */
@Composable
private fun StartWizardBar(onClick: () -> Unit, enabled: Boolean = true) {
    BottomActionBar {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("이 대회로 동선 만들기", style = MaterialTheme.typography.titleMedium)
        }
    }
}
