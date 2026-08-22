package com.runninggu.app.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 원천 출처 표기. (SPEC §4.11-5 · 결정-44)
 *
 * **순서와 문구를 바꾸지 않고 그대로 낸다** — 공공누리·ODbL 이 요구하는 의무 표기라,
 * 앱이 다듬으면 라이선스 위반이 될 수 있다. 서버가 검증한 완성 문구만 배열로 오므로
 * 앱은 `" · "` 로 잇기만 한다.
 *
 * 빈 배열이면 아무것도 그리지 않는다 — "출처 ·" 만 남으면 더 이상하다.
 *
 * 쓰는 곳이 둘이라 `ui/common` 에 둔다.
 * - S8 러닝코스 목록 하단 (`GET /courses/near` · 지역별) — 응답마다 실제 쓰인 원천만 온다
 * - 저장 코스 상세 (`GET /me/courses/{id}`) — **저장 시점 snapshot** 이라 외부 문구가
 *   바뀌어도 그대로다(결정-44)
 */
@Composable
fun Attributions(
    attributions: List<String>,
    modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
) {
    val text = attributionsText(attributions) ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * 표기 문구. 그릴 게 없으면 null 이다.
 *
 * 컴포저블에서 빼 둔 것은 **규칙을 단위 테스트로 고정**하기 위해서다 — 순서를 바꾸거나
 * 문구를 다듬으면 라이선스 의무를 어기는 자리라, 화면 없이도 확인할 수 있어야 한다.
 */
internal fun attributionsText(attributions: List<String>): String? =
    if (attributions.isEmpty()) null else "출처 · " + attributions.joinToString(" · ")
