package com.runninggu.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Loading / Empty / Error 공용 표시. (SPEC §3-5)
 *
 * 정상 빈 결과는 [EmptyState], 네트워크·서버 오류는 [ErrorState]로 구분해서 쓴다.
 * 오류를 빈 상태나 무한 Loading으로 강등하지 않는다.
 */
@Composable
fun LoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyState(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (description != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 오류 상태. (SPEC §3-5)
 *
 * **[onRetry] 를 안 주면 [다시 시도] 를 그리지 않는다.** 다시 눌러도 소용없는 오류가
 * 있기 때문이다 — 대회에 좌표가 없어 인근 축제를 못 찾는 `409 CONTEST_LOCATION_UNAVAILABLE`
 * 이 그렇다(API 명세 §3-5). 좌표는 재시도로 생기지 않는다.
 *
 * **그럴 때 [EmptyState] 로 대신 그리면 안 된다.** 명세가 그 자리를 이렇게 못 박는다.
 *
 * > `409 CONTEST_LOCATION_UNAVAILABLE` 은 **빈 상태가 아니라 재시도 버튼이 없는 별도
 * > 오류 상태**로 그린다 — (…) 그렇다고 "축제가 없어요" 로 적으면 사실과 다르다.
 *
 * 빈 상태와 오류는 **색과 크기가 다르다**(여기는 `error` 색 `bodyMedium`, 빈 상태는
 * 기본색 `titleMedium`). 뭉뚱그리면 "없는 것" 과 "못 불러온 것" 이 같아 보인다.
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRetry) { Text("다시 시도") }
        }
    }
}

/** 섹션 헤더 — 제목 + 우측 보조 문구. 목업 secHead 승계. */
@Composable
fun SectionHeader(
    title: String,
    trailing: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
