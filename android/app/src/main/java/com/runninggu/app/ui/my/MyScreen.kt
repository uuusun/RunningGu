package com.runninggu.app.ui.my

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.data.model.SavedItinerary
import com.runninggu.app.domain.today
import com.runninggu.app.ui.calendar.RaceCard
import com.runninggu.app.ui.common.EmptyState
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState

/**
 * S10 마이(보관함). (SPEC §4.13 · AP-13)
 *
 * 프로필 요약 + 설정(계정 관리 진입, D-22 · F-05) + 세그먼트 [동선]|[러닝코스]|[찜한 대회].
 * 마이 진입 자체가 로그인 필요라(결정-4) 게스트는 로그인 유도만 본다.
 */
@Composable
fun MyScreen(
    onLoginRequest: () -> Unit,
    onOpenAccount: () -> Unit,
    onRaceClick: (String) -> Unit,
    onCourseClick: (Long) -> Unit,
    /**
     * 저장 동선 카드 → S7-R. (§5-5 · #213)
     *
     * `SavedItinerary.id` 가 String 이라 그대로 넘긴다. 숫자로 바꾸는 것은 route 를
     * 만드는 쪽이 한다 — 삭제(`onDeleteItinerary`)도 같은 자리에서 바꾼다.
     */
    onItineraryClick: (String) -> Unit,
    onBrowseRaces: () -> Unit,
    onBrowseCourses: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val profile = state.profile

    // 상세에서 코스를 지우고 돌아오면 목록이 달라져 있다. 화면이 다시 앞으로 나올 때
    // 재조회한다 — 마이는 back stack 에 남아 ViewModel 이 그대로라 스스로는 안 바뀐다.
    // "처음인가" 판단은 ViewModel 이 한다. 여기서 remember 로 세면 상세로 나갈 때
    // 컴포지션이 걷히면서 같이 지워져, 돌아와도 첫 진입으로 보인다.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    // 찜 해제 실패 등을 알린다. 하트는 롤백돼 되돌아오는데 이유가 없으면 오작동으로 보인다.
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    if (profile == null) {
        GuestGate(onLoginRequest = onLoginRequest, modifier = modifier)
        return
    }

    Box(modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        ProfileHeader(profile = profile, onOpenAccount = onOpenAccount)

        Spacer(Modifier.height(16.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            MySegment.entries.forEachIndexed { index, segment ->
                SegmentedButton(
                    selected = segment == state.segment,
                    onClick = { viewModel.onSegmentSelect(segment) },
                    shape = SegmentedButtonDefaults.itemShape(index, MySegment.entries.size),
                ) { Text(segment.label) }
            }
        }

        Spacer(Modifier.height(16.dp))
        when (state.segment) {
            MySegment.ITINERARY -> ItineraryList(
                state = state.itineraries,
                onItineraryClick = onItineraryClick,
                onDelete = viewModel::onDeleteItinerary,
                onBrowseRaces = onBrowseRaces,
                onRetry = viewModel::loadItineraries,
                onLoadMore = viewModel::loadMoreItineraries,
            )

            MySegment.COURSE -> CourseList(
                state = state.courses,
                onCourseClick = onCourseClick,
                onBrowseCourses = onBrowseCourses,
                onRetry = viewModel::loadCourses,
                onLoadMore = viewModel::loadMoreCourses,
            )

            MySegment.FAVORITE -> FavoriteList(
                state = state.favorites,
                favoriteIds = state.favoriteIds,
                onRaceClick = onRaceClick,
                onFavoriteToggle = viewModel::onFavoriteToggle,
                onBrowseRaces = onBrowseRaces,
                onRetry = viewModel::loadFavorites,
                onLoadMore = viewModel::loadMoreFavorites,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

/** 게스트 로그인 유도. 마이는 로그인 필요다 (SPEC 결정-4). */
@Composable
private fun GuestGate(onLoginRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "로그인이 필요해요",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "동선과 코스를 저장하고 대회를 찜하려면 로그인해 주세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onLoginRequest) { Text("로그인하기") }
    }
}

/**
 * 프로필 요약 — 닉네임 · 가입 로그인 방식 · 대표 이메일(있을 때만). (SPEC §4.13 · #59)
 *
 * `email`이 null(카카오 미제공)이면 이메일 행 자체를 숨기고 placeholder를 두지 않는다.
 */
@Composable
private fun ProfileHeader(profile: SessionProfile, onOpenAccount: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.nickname,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = profile.loginProvider.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            profile.email?.let { email ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onOpenAccount) {
            Icon(Icons.Default.Settings, contentDescription = "계정 관리")
        }
    }
}

// ── [동선] (SPEC §4.13) ─────────────────────────────────────────

/**
 * 동선 삭제 확인. (매핑표 S10 `동선 삭제 — 확인 modal, 실패 시 유지`)
 *
 * 제목을 넣는 이유는 저장 코스 삭제와 같다 — 목록에서 여러 개를 보다 누른 사용자가
 * **어느 것을 지우는지** 확인할 수 있어야 한다.
 *
 * 실패는 여기서 다루지 않는다. 서버가 지운 뒤에 목록에서 빼므로(`MyViewModel`),
 * 실패하면 카드가 그대로 남고 스낵바만 뜬다 — 그게 "실패 시 유지" 다.
 */
@Composable
private fun DeleteItineraryDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("저장한 동선을 지울까요?") },
        text = { Text("‘$title’을 마이에서 지웁니다. 되돌릴 수 없어요.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("삭제") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * **`internal` 인 이유** — 삭제 확인 모달이 서버 요청을 실제로 막는지는 화면을 그려야만
 * 확인된다. `EditList` 를 계측 테스트가 직접 그리는 것과 같은 방식이다(#71).
 */
@Composable
internal fun ItineraryList(
    state: SavedItinerariesState,
    /** 카드 → S7-R. (§5-5 · #213) */
    onItineraryClick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBrowseRaces: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    /**
     * 지울 동선을 고른 상태. **확인 전에는 서버 요청이 나가지 않는다** (매핑표 S10 · #181 리뷰).
     *
     * 동선은 되돌릴 수 없는 사용자 데이터라 휴지통이 곧바로 `DELETE` 를 쏘면 안 된다.
     * 저장 코스 상세가 이미 같은 규칙을 쓴다(`CourseDetailScreen` 의 삭제 확인).
     */
    var pendingDelete by remember { mutableStateOf<SavedItinerary?>(null) }

    pendingDelete?.let { target ->
        DeleteItineraryDialog(
            title = target.title,
            onConfirm = {
                pendingDelete = null
                onDelete(target.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // 조회 중·실패를 빈 상태로 뭉뚱그리지 않는다 (§3-5). 저장 코스와 같은 규칙이다.
    when (state) {
        SavedItinerariesState.Loading -> {
            LoadingState("저장한 동선을 불러오는 중…")
            return
        }

        SavedItinerariesState.Empty -> {
            EmptyState("아직 저장한 동선이 없어요.")
            BrowseButton("대회 둘러보기", onBrowseRaces)
            return
        }

        is SavedItinerariesState.Error -> {
            ErrorState(message = state.message, onRetry = onRetry)
            return
        }

        is SavedItinerariesState.Content -> Unit
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.itineraries.forEach { item ->
            Surface(
                // 탭 → 저장 상태 복원 → S7-R (§5-5 · D-14 · #213).
                // **비활성 대회의 동선도 열린다** — 목록에서 안 지우기로 했으므로(§5-4)
                // 열지도 못하게 하면 사용자가 지우거나 볼 방법이 없다
                onClick = { onItineraryClick(item.id) },
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            item.recoveryLabel?.let { label ->
                                Spacer(Modifier.width(6.dp))
                                CardBadge(
                                    text = label,
                                    container = MaterialTheme.colorScheme.tertiaryContainer,
                                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                            // 저장 당시와 대회가 달라졌다 — 다시 만들어야 한다 (§5-4)
                            if (item.needsRegeneration) {
                                Spacer(Modifier.width(6.dp))
                                CardBadge(
                                    text = "대회 변경",
                                    container = MaterialTheme.colorScheme.errorContainer,
                                    content = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                        // 비활성 대회의 동선도 목록에서 지우지 않는다 (§5-4). 왜 그런지만 알린다
                        if (!item.active) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "정보 제공이 끝난 대회예요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${item.raceName} · ${item.event}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${item.period} · ${item.placeCount}곳",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { pendingDelete = item }) {
                        Icon(Icons.Default.Delete, contentDescription = "삭제")
                    }
                }
            }
        }

        // 한 번에 20건씩 온다 — 더 있으면 눌러서 이어 받는다 (API 명세 §0-4).
        // 받는 동안은 잠그고 그렇게 말한다 — [러닝코스] 와 같은 모양이다 (§3-5).
        if (state.hasNext) {
            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                onClick = onLoadMore,
                enabled = state.canLoadMore,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.loadingMore) {
                        "불러오는 중…"
                    } else {
                        "더 보기 (${state.itineraries.size}/${state.totalElements})"
                    },
                )
            }
        }

        // 다음 장을 못 받았다. 위 목록은 그대로 두고 이 줄만 붙인다.
        state.moreMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ── [러닝코스] — P0는 saved만 (SPEC §4.13 · D-25) ───────────────

@Composable
private fun CourseList(
    state: SavedCoursesState,
    onCourseClick: (Long) -> Unit,
    onBrowseCourses: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    // 로딩·빈·오류를 구분한다 — 뭉뚱그리면 "없는 것" 과 "못 불러온 것" 이 같아 보인다
    // (SPEC §3-5 · #107 리뷰).
    when (state) {
        SavedCoursesState.Loading -> {
            LoadingState("저장한 코스를 불러오는 중…")
            return
        }

        SavedCoursesState.Empty -> {
            EmptyState("저장한 코스가 없어요.")
            BrowseButton("러닝코스 둘러보기", onBrowseCourses)
            return
        }

        is SavedCoursesState.Error -> {
            ErrorState(message = state.message, onRetry = onRetry)
            return
        }

        is SavedCoursesState.Content -> Unit
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.courses.forEach { course ->
            Surface(
                // 탭 → 저장 코스 상세. 경로·고도·출처는 거기서만 본다 (matrix S8-D · D-20).
                onClick = { onCourseClick(course.id) },
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = course.courseName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        // 출처(attributions)는 카드에 안 낸다 — 상세 응답에만 온다(결정-44).
                        text = "왕복 ${course.distanceKm}km · 상승 ${course.gainM}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 한 번에 20건씩 온다 — 더 있으면 눌러서 이어 받는다 (API 명세 §0-4).
        if (state.hasNext) {
            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                onClick = onLoadMore,
                enabled = state.canLoadMore,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.loadingMore) {
                        "불러오는 중…"
                    } else {
                        "더 보기 (${state.courses.size}/${state.totalElements})"
                    },
                )
            }
        }

        // 다음 장을 못 받았다. 위 목록은 그대로 두고 이 줄만 붙인다.
        state.moreMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ── [찜한 대회] — S2 카드 재사용 (SPEC §4.13 · 결정-16 · AP-21) ──

@Composable
private fun FavoriteList(
    state: FavoriteRacesState,
    favoriteIds: Set<String>,
    onRaceClick: (String) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onBrowseRaces: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
) {
    // 러닝코스와 같은 규칙이다 — 뭉뚱그리면 "찜한 게 없는 것" 과 "못 불러온 것" 이
    // 같아 보인다 (SPEC §3-5 · #163).
    when (state) {
        FavoriteRacesState.Loading -> {
            LoadingState("찜한 대회를 불러오는 중…")
            return
        }

        FavoriteRacesState.Empty -> {
            EmptyState("찜한 대회가 없어요.")
            BrowseButton("대회 둘러보기", onBrowseRaces)
            return
        }

        is FavoriteRacesState.Error -> {
            ErrorState(message = state.message, onRetry = onRetry)
            return
        }

        is FavoriteRacesState.Content -> Unit
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.races.forEach { race ->
            // 지난 대회·비활성 대회 흐림은 RaceCard 가 스스로 한다. 여기서 따로 alpha 를
            // 걸면 두 벌이 되어 값이 갈린다 (SPEC §4.13 · API 명세 §7-C 🔒).
            RaceCard(
                race = race,
                // **목록에 있다는 것으로 단정하지 않는다.** 여기서 하트를 끄면 카드는
                // 남고 하트만 꺼져야 다시 켤 수 있다 (#163).
                isFavorite = race.id in favoriteIds,
                onClick = { onRaceClick(race.id) },
                onFavoriteToggle = { onFavoriteToggle(race.id) },
            )
        }

        // 한 번에 20건씩 온다 — 더 있으면 눌러서 이어 받는다 (API 명세 §0-4).
        if (state.hasNext) {
            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                onClick = onLoadMore,
                enabled = state.canLoadMore,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.loadingMore) {
                        "불러오는 중…"
                    } else {
                        "더 보기 (${state.races.size}/${state.totalElements})"
                    },
                )
            }
        }

        // 다음 장을 못 받았다. 위 목록은 그대로 두고 이 줄만 붙인다.
        state.moreMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun BrowseButton(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = onClick) { Text(label) }
    }
}

/** 카드 제목 옆 작은 배지. 회복 라벨과 "대회 변경" 이 같은 모양을 쓴다. (SPEC §4.13) */
@Composable
private fun CardBadge(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Surface(color = container, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
