package com.runninggu.app.ui.course

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runninggu.app.data.model.SavedCourse
import com.runninggu.app.data.model.SavedCourseDetail
import com.runninggu.app.ui.common.Attributions
import com.runninggu.app.ui.common.ElevationLine
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState
import java.time.format.DateTimeFormatter

/**
 * S8-D 저장 코스 상세. (matrix S8-D `saved` · §7-A · §4.13)
 *
 * 목록 카드가 못 담는 것 셋을 보여준다 — **경로**·**고도 프로필**·**출처**. 그 셋 때문에
 * 상세가 따로 있는 것이고, 특히 출처는 저장 시점 snapshot 이라 여기서만 볼 수 있다(결정-44).
 *
 * 지도(경로 점선)는 자리만 두었다 — AP-03 카카오맵이 아직 develop 에 없다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    onBack: () -> Unit,
    viewModel: CourseDetailViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 삭제가 끝나면 목록으로. 화면이 스스로 닫힌다.
    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    // 삭제 실패처럼 내용은 그대로 두고 알리기만 하는 오류.
    LaunchedEffect(state.errorMessage, state.phase) {
        val message = state.errorMessage
        if (message != null && state.phase == CourseDetailUiState.Phase.CONTENT) {
            snackbarHostState.showSnackbar(message)
            viewModel.onErrorShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("저장한 코스", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    if (state.phase == CourseDetailUiState.Phase.CONTENT) {
                        IconButton(
                            onClick = viewModel::onDeleteRequest,
                            enabled = !state.deleting,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "저장 코스 삭제")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when (state.phase) {
                CourseDetailUiState.Phase.LOADING -> LoadingState("불러오는 중…")

                CourseDetailUiState.Phase.ERROR -> ErrorState(
                    message = state.errorMessage ?: "코스를 못 불러왔어요.",
                    onRetry = viewModel::load,
                )

                CourseDetailUiState.Phase.CONTENT ->
                    state.detail?.let { Content(detail = it) }
            }
        }
    }

    if (state.pendingDelete) {
        DeleteConfirmDialog(
            courseName = state.detail?.course?.courseName.orEmpty(),
            deleting = state.deleting,
            onConfirm = viewModel::onDeleteConfirm,
            onDismiss = viewModel::onDeleteCancel,
        )
    }
}

@Composable
private fun Content(detail: SavedCourseDetail) {
    val course = detail.course
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        MapPlaceholder()

        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = course.courseName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitleOf(course),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            StatRow(course)

            Spacer(Modifier.height(20.dp))
            Text(
                text = "고도",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            ElevationLine(
                seed = course.id.toInt(),
                closed = false,
                profile = detail.elevationProfileM.map { it.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            )
        }

        // 출처는 목록 하단과 같은 규칙이다 — 순서·문구를 바꾸지 않는다 (결정-44).
        Attributions(detail.attributions)
        Spacer(Modifier.height(24.dp))
    }
}

/** "{지역} · MM.DD 저장" — 지역이 없으면 저장일만. */
private fun subtitleOf(course: SavedCourse): String {
    val saved = course.savedAt.format(SAVED_AT_FORMAT) + " 저장"
    return course.region?.let { "$it · $saved" } ?: saved
}

@Composable
private fun StatRow(course: SavedCourse) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Stat("거리", "%.1fkm".format(course.distanceKm))
        Stat("예상 시간", "${course.durationMin}분")
        Stat("상승", "${course.gainM}m")
        // 난이도는 표시용이다 — 원천마다 기준이 달라 정렬·필터에 쓰지 않는다 (결정-42).
        course.difficulty?.let { Stat("난이도", it.label) }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 경로 점선이 들어올 자리. (matrix S8-D "코스 상세 **점선** 렌더링" 🔒)
 *
 * `ui/map` 이 develop 에 들어오면(AP-03) `RunningGuMap` 으로 바꾼다. `pathPolyline` 은
 * 이미 받아 두었으니 화면 쪽만 갈아끼우면 된다.
 */
@Composable
private fun MapPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "지도는 준비 중이에요",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 삭제 확인. (§4.13 `[삭제]` 🔧정책)
 *
 * 저장 코스는 되돌릴 수 없으므로 한 번 묻는다. 코스 이름을 넣는 이유는, 목록에서 여러 개를
 * 보다 들어온 사용자가 **어느 것을 지우는지** 확인할 수 있어야 하기 때문이다.
 */
@Composable
private fun DeleteConfirmDialog(
    courseName: String,
    deleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("저장한 코스를 지울까요?") },
        text = { Text("‘$courseName’을 마이에서 지웁니다. 되돌릴 수 없어요.") },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                Text(if (deleting) "지우는 중…" else "삭제")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text("취소") }
        },
    )
}

private val SAVED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd")
