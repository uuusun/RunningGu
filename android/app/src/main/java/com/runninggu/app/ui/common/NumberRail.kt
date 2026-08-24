package com.runninggu.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 목록 순번을 담는 동그란 번호. (SPEC §3-8 · §4.11-4)
 *
 * **두 화면이 같은 뜻(순번)에 같은 모양을 쓰게 하려고 여기 둔다.**
 *
 * - S7 동선 타임라인 — 블록 순서. 지도 핀 번호와 같은 값이다 (§3-8)
 * - S8 내 주변 목록 — 거리순 순번. 역시 지도 번호 핀과 같은 값이다 (§4.11-4 "리스트 번호 일치")
 *
 * 화면마다 복사해 두면 한쪽 지름이나 색을 바꿨을 때 다른 쪽만 예전 모양으로 남고 아무도
 * 모른다 — 같은 모양으로 두려던 것이 거기서 깨진다(#158 리뷰).
 */
@Composable
fun NumberRail(number: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$number",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
