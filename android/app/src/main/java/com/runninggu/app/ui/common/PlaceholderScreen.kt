package com.runninggu.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 아직 구현하지 않은 화면 자리. 내비게이션 뼈대(AP-06)를 먼저 확인하기 위한 것이며,
 * 각 화면 작업에서 실제 Composable로 교체한다.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(text = description, style = MaterialTheme.typography.bodyMedium)
    }
}
