package com.runninggu.app.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 데이터 출처 배지 — `LIVE` · `SAMPLE` · `SYNTH`. (SPEC §6.3 · NFR-2)
 *
 * S6 숙소·S7 후보 시트 등 POI 목록이 나오는 화면 공통이다. 화면마다 복제하지 않는다 —
 * 배지는 공통 시각 계약이라 한쪽만 바뀌면 화면끼리 조용히 갈라진다.
 */
@Composable
fun SourceBadge(source: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier,
    ) {
        Text(
            text = source,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
