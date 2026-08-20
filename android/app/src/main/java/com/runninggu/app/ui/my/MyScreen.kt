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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runninggu.app.data.local.SessionProfile
import com.runninggu.app.ui.calendar.RaceCard
import com.runninggu.app.ui.common.EmptyState

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
    onBrowseRaces: () -> Unit,
    onBrowseCourses: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val profile = state.profile

    if (profile == null) {
        GuestGate(onLoginRequest = onLoginRequest, modifier = modifier)
        return
    }

    Column(
        modifier
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
                items = state.itineraries,
                onDelete = viewModel::onDeleteItinerary,
                onBrowseRaces = onBrowseRaces,
            )

            MySegment.COURSE -> CourseList(
                items = state.courses,
                onBrowseCourses = onBrowseCourses,
            )

            MySegment.FAVORITE -> FavoriteList(
                races = state.favoriteRaces,
                onRaceClick = onRaceClick,
                onFavoriteToggle = viewModel::onFavoriteToggle,
                onBrowseRaces = onBrowseRaces,
            )
        }
        Spacer(Modifier.height(24.dp))
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

@Composable
private fun ItineraryList(
    items: List<SavedItinerary>,
    onDelete: (String) -> Unit,
    onBrowseRaces: () -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState("아직 저장한 동선이 없어요.")
        BrowseButton("대회 둘러보기", onBrowseRaces)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { item ->
            Surface(
                // TODO(AP-14): 탭 → 저장 상태 복원 → S7 (API 명세 §5-3, D-14). 서버 조회가 붙는
                //  연동에서 연결한다 — 지금 데모 데이터는 복원할 응답이 없다.
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
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = MaterialTheme.shapes.extraSmall,
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
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
                    IconButton(onClick = { onDelete(item.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "삭제")
                    }
                }
            }
        }
    }
}

// ── [러닝코스] — P0는 saved만 (SPEC §4.13 · D-25) ───────────────

@Composable
private fun CourseList(
    items: List<SavedCourse>,
    onBrowseCourses: () -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState("저장한 코스가 없어요.")
        BrowseButton("러닝코스 둘러보기", onBrowseCourses)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { course ->
            Surface(
                // TODO(AP-12/14): 탭 → 경로 지도 상세 (D-20 sealed CourseDetailKey).
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = course.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "왕복 ${course.distanceKm}km · 상승 ${course.gainM}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── [찜한 대회] — S2 카드 재사용 (SPEC §4.13 · 결정-16) ─────────

@Composable
private fun FavoriteList(
    races: List<com.runninggu.app.ui.model.RaceSummary>,
    onRaceClick: (String) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onBrowseRaces: () -> Unit,
) {
    if (races.isEmpty()) {
        EmptyState("찜한 대회가 없어요.")
        BrowseButton("대회 둘러보기", onBrowseRaces)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        races.forEach { race ->
            RaceCard(
                race = race,
                isFavorite = true, // 이 목록에 있다는 것 자체가 찜 상태다
                onClick = { onRaceClick(race.id) },
                onFavoriteToggle = { onFavoriteToggle(race.id) },
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
