package com.runninggu.app.ui.course

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runninggu.app.data.model.CuratedCourseDetail
import com.runninggu.app.domain.LatLng
import com.runninggu.app.ui.common.Attributions
import com.runninggu.app.ui.common.ElevationLine
import com.runninggu.app.ui.common.EmptyState
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState
import com.runninggu.app.ui.common.elevationUnitProfile
import com.runninggu.app.ui.map.MapScene
import com.runninggu.app.ui.map.RunningGuMap

/**
 * S8-D 큐레이션 코스 상세. (#280 · 매핑표 S8-D)
 *
 * ## 왜 이 화면이 생겼나
 *
 * 지역별 목록이 "전국 코스 261" 을 보여주면서 **아무것도 못 누르는 화면**이었다. 목록
 * 응답(§6-2)에 좌표가 없어서 눌러도 갈 곳을 만들 수 없었기 때문이다. 서버가
 * `GET /api/courses/{courseId}` 로 원본 전체 경로를 주기로 하면서(#280) 풀렸다.
 *
 * ## 저장 코스 상세와 나눈 이유
 *
 * [CourseDetailScreen] 과 생김새가 거의 같지만 **하는 일이 다르다** — 저장 코스는 지울 수
 * 있고 이것은 읽기만 한다. 한 화면으로 묶으면 삭제 버튼을 조건부로 숨겨야 하고, 그러면
 * "무엇을 지우는 화면인가" 가 흐려진다. 그릴 조각([ElevationLine] · 지도)은 공용이다.
 *
 * **저장 버튼은 아직 없다.** §4.11-6 의 저장은 `near` 왕복 경로를 대상으로 정의돼 있고,
 * 원본 전체 코스를 저장하는 계약은 아직 없다. 넣으려면 계약부터다(AGENTS 4장).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CuratedCourseDetailScreen(
    courseId: String,
    onBack: () -> Unit,
    viewModel: CuratedCourseDetailViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 같은 id 면 ViewModel 이 막는다 — 회전·재진입으로 여기가 다시 돈다
    LaunchedEffect(courseId) { viewModel.load(courseId) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.detail?.courseName ?: "코스") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            when (state.phase) {
                CuratedCourseDetailUiState.Phase.LOADING ->
                    LoadingState(message = "코스를 불러오는 중…")

                CuratedCourseDetailUiState.Phase.ERROR ->
                    ErrorState(
                        message = state.errorMessage ?: "코스를 불러오지 못했어요.",
                        onRetry = viewModel::retry,
                    )

                CuratedCourseDetailUiState.Phase.CONTENT ->
                    state.detail?.let { Content(it) }
            }
        }
    }
}

@Composable
private fun Content(detail: CuratedCourseDetail) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        CourseLine(detail.path)

        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = detail.courseName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = regionOf(detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("거리", "%.1fkm".format(detail.distanceKm))
                detail.durationMin?.let { Stat("예상 시간", "${it}분") }
                detail.gainM?.let { Stat("상승", "${it}m") }
                // 난이도는 표시용이다 — 원천마다 기준이 달라 정렬·필터에 쓰지 않는다 (결정-42).
                // **여기 등급은 원본 코스 전체 기준**이라 `near` 의 구간 등급과 달라도 정상이고,
                // 그래서 `HARD` 도 그대로 나온다 (§4.11-b).
                detail.difficulty?.let { Stat("난이도", it.label) }
            }

            if (detail.elevationProfileM.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("고도", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                ElevationLine(
                    seed = detail.courseId.hashCode(),
                    closed = false,
                    // 미터 원값을 그대로 넘기면 안 된다 — `ElevationLine` 은 0..1 을 받는다 (#268)
                    profile = elevationUnitProfile(detail.elevationProfileM),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
            }
        }

        // 출처는 목록 하단과 같은 규칙이다 — 순서·문구를 바꾸지 않는다 (결정-44).
        Attributions(detail.attributions)
        Spacer(Modifier.height(24.dp))
    }
}

/** 통계 한 칸. 저장 코스 상세의 같은 조각과 생김새를 맞춘다. */
@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

/**
 * 지역 표기. **`sigun` 이 이미 시도를 품고 있다.** (#286 기기 확인)
 *
 * 서버가 두루누비 catalog 를 그대로 주는데 두 필드가 이렇게 온다.
 *
 * ```
 * sido = "강원"   sigun = "강원 양구군"
 * sido = "부산"   sigun = "부산 중구"
 * ```
 *
 * 그래서 둘을 이어붙이면 `강원 강원 양구군` 이 된다 — 실제로 그렇게 나왔다.
 * **지역별 목록([courseSubtitle])은 처음부터 `sigun` 만 쓰고 있었다.** 상세만 달랐다.
 *
 * `sigun` 이 없을 때만 `sido` 로 물러선다 — 계약상 둘 다 nullable 이다(§6-4).
 */
private fun regionOf(detail: CuratedCourseDetail): String =
    regionLabel(sido = detail.sido, sigun = detail.sigun)

/** 화면 밖에서 고정할 수 있게 값만 받는다 — Compose 안에 두면 단위 테스트가 안 닿는다. */
internal fun regionLabel(sido: String?, sigun: String?): String =
    sigun?.takeIf { it.isNotBlank() }
        ?: sido?.takeIf { it.isNotBlank() }
        ?: "지역 정보 없음"

/**
 * 코스 경로선. **핀 없이 선만** 그린다 (SPEC §3-8).
 *
 * 점이 2개가 안 되면 지도 대신 이유를 적는다 — 디코더가 깨진 입력에 읽은 만큼만
 * 돌려주기 때문에 "필드가 없다" 가 아니라 "풀었더니 짧다" 를 본다(#209 와 같은 판단).
 */
@Composable
private fun CourseLine(path: List<LatLng>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().height(240.dp),
    ) {
        if (path.size < 2) {
            EmptyState(title = "이 코스의 경로를 그릴 수 없어요.")
        } else {
            RunningGuMap(scene = MapScene(route = path), modifier = Modifier.fillMaxSize())
        }
    }
}
