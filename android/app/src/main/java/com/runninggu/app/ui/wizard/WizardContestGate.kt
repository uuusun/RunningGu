package com.runninggu.app.ui.wizard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.runninggu.app.ui.common.EmptyState
import com.runninggu.app.ui.common.ErrorState
import com.runninggu.app.ui.common.LoadingState
import com.runninggu.app.ui.model.RaceSummary

/**
 * 대회 조회 상태로 **무엇을 그릴지**. (이슈 #140 · #189 후속 · SPEC §3-5)
 *
 * 판정을 값으로 뺀 이유는 `ContestBundle.parse` 와 같다 — **Android 없이 테스트할 수
 * 있어야 한다.** Compose 안에 `when` 으로만 두면 계측 테스트가 되는데, CI 가 그걸 안
 * 돌린다(AGENTS 3장 · 이슈 #83). "`NOT_FOUND` 에는 재시도를 주지 않는다" 같은 결정은
 * 코드가 아니라 **테스트가** 지켜야 한다.
 */
sealed interface WizardContestView {

    data object Loading : WizardContestView

    /**
     * 못 불러왔다.
     *
     * @param retryable 다시 눌러 볼 값어치가 있는가. **이 하나가 `ERROR` 와 `NOT_FOUND`
     *  를 가르는 기준 전부**다 — 없는 대회는 다시 눌러도 생기지 않아 헛돈다(#139 · #189).
     */
    data class Failed(
        val title: String,
        val description: String?,
        val retryable: Boolean,
    ) : WizardContestView

    data class Ready(val race: RaceSummary) : WizardContestView
}

/**
 * 문구와 재시도 여부는 **S3 와 같다.** 같은 `GET /api/contests/{id}` 를 보는 화면이라
 * 사용자가 겪는 일이 같다 — S3 가 #139 에서 정한 것을 그대로 쓴다.
 */
fun WizardUiState.contestView(): WizardContestView = when (contestPhase) {
    WizardUiState.Phase.LOADING -> WizardContestView.Loading

    WizardUiState.Phase.ERROR -> WizardContestView.Failed(
        // 서버가 준 말이 있으면 그걸 쓴다 — 왜 실패했는지는 서버가 더 잘 안다.
        title = errorMessage ?: "대회 정보를 못 불러왔어요.",
        description = null,
        retryable = true,
    )

    WizardUiState.Phase.NOT_FOUND -> WizardContestView.Failed(
        title = "대회 정보를 찾을 수 없어요.",
        description = "삭제됐거나 주소가 잘못됐을 수 있어요.",
        retryable = false,
    )

    // `LOADED` 는 대회를 실은 자리에서만 세우므로(`WizardViewModel.load`) race 가 null 일
    // 수 없다. 그래도 크래시 대신 로딩으로 둔다 — 화면이 상태 불변을 강제할 자리는 아니다.
    WizardUiState.Phase.LOADED -> race?.let(WizardContestView::Ready) ?: WizardContestView.Loading
}

/**
 * 위저드 화면들이 대회 조회 상태를 그리는 **한 자리**. (이슈 #140 · #189 후속)
 *
 * S4~S7 이 전부 이 대회 위에 서므로 못 불러오면 위저드를 통째로 못 쓴다. 화면마다 따로
 * 그리면 한 곳만 고쳐지고 나머지가 남아서 여기 모은다.
 */
@Composable
fun WizardContestGate(
    state: WizardUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (RaceSummary) -> Unit,
) {
    when (val view = state.contestView()) {
        WizardContestView.Loading -> LoadingState("불러오는 중…", modifier)

        is WizardContestView.Failed ->
            if (view.retryable) {
                ErrorState(message = view.title, onRetry = onRetry, modifier = modifier)
            } else {
                // 재시도 없는 실패는 오류가 아니라 "없는 것" 으로 그린다 — S3 와 같다(#139).
                EmptyState(title = view.title, description = view.description, modifier = modifier)
            }

        is WizardContestView.Ready -> content(view.race)
    }
}
