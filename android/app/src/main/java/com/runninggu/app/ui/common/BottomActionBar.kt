package com.runninggu.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 화면 아래에 고정되는 액션 바. 탭바가 없는 화면(S3 상세 · 위저드 S4~S7)이 쓴다. (이슈 #266)
 *
 * ## 왜 공통으로 묶었나
 *
 * 다섯 화면이 `Surface(shadowElevation = 8.dp) { Button(padding(20,12).height(52)) }` 를
 * **각자 적어 두고 있었다.** 그리고 다섯 곳 모두 [navigationBarsPadding] 을 빠뜨렸다.
 *
 * `MainActivity` 가 `enableEdgeToEdge()` 를 켜므로 앱이 시스템 바 아래까지 그린다.
 * inset 을 직접 먹지 않으면 **버튼 아래 절반이 내비게이션 바에 가린다.** 3버튼 내비
 * 기기에서는 버튼 한가운데를 눌러도 반응하지 않고 위쪽 가장자리를 정확히 눌러야 했다 —
 * S3 의 [이 대회로 동선 만들기] 가 그랬으니 **P0 핵심 흐름의 진입점이 막혀 있었다**(#266).
 *
 * 탭바(`RunningGuApp`)만 멀쩡했던 것은 Material3 `NavigationBar` 가 자기 inset 을 알아서
 * 먹기 때문이다. 그래서 4탭 화면만 밟으면 끝까지 안 보인다.
 *
 * **한 곳만 고치면 나머지 넷이 남는다.** 그래서 값을 한 벌로 모으고 화면은 내용만 넣는다.
 *
 * ## 무엇을 담나
 *
 * 버튼 하나가 보통이지만 [ColumnScope] 로 열어 둔다 — S7 은 저장 실패 문구를 버튼 아래
 * 같은 바 안에 붙인다. 바깥에 두면 그림자 경계 위로 떠서 다른 층처럼 보인다.
 */
@Composable
fun BottomActionBar(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Column(
            modifier
                // **패딩보다 먼저 온다.** 뒤에 두면 좌우 20dp 안쪽에서 inset 을 먹어
                // 버튼이 화면 가장자리까지 늘어난다
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            content = content,
        )
    }
}
