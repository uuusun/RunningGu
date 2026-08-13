package com.runninggu.app.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.runninggu.app.ui.model.EVENT_TYPES
import com.runninggu.app.ui.model.REGIONS

/**
 * 필터 바텀시트. (SPEC §4.5)
 *
 * 종목(복수) · "접수 가능만" 토글 · 지역 17개 시도(복수).
 * 변경은 초안에만 반영하고 [완료]를 눌러야 적용된다 — [취소]는 변경을 버린다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceFilterSheet(
    initial: RaceFilter,
    onDismiss: () -> Unit,
    onApply: (RaceFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(initial) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "필터",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(20.dp))
            FilterGroupTitle("키로수")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EVENT_TYPES.forEach { event ->
                    FilterChip(
                        selected = event in draft.events,
                        onClick = {
                            draft = draft.copy(
                                events = draft.events.toggle(event),
                            )
                        },
                        label = { Text(event) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    FilterGroupTitle("모집 마감 여부")
                    Text(
                        text = "접수 가능한 대회만 보기",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = draft.openOnly,
                    onCheckedChange = { draft = draft.copy(openOnly = it) },
                )
            }

            Spacer(Modifier.height(20.dp))
            FilterGroupTitle("지역")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                REGIONS.forEach { region ->
                    FilterChip(
                        selected = region in draft.regions,
                        onClick = {
                            draft = draft.copy(
                                regions = draft.regions.toggle(region),
                            )
                        },
                        label = { Text(region) },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { draft = RaceFilter() }) { Text("초기화") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onDismiss) { Text("취소") }
                Button(onClick = { onApply(draft) }) { Text("완료") }
            }
        }
    }
}

@Composable
private fun FilterGroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value
