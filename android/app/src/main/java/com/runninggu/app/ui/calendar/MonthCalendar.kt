package com.runninggu.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.runninggu.app.domain.today
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val DOW_KO = listOf("일", "월", "화", "수", "목", "금", "토")
private val SundayRed = Color(0xFFE5484D)
private val SaturdayBlue = Color(0xFF2B5CFF)

/**
 * 월간 캘린더. (SPEC §4.5)
 *
 * 월 네비 · 요일 헤더(일 빨강·토 파랑) · 대회 있는 날 점(2건 이상이면 건수) · 오늘 테두리.
 * 날짜를 다시 누르면 선택이 해제되고, 월을 옮기면 선택이 초기화된다.
 */
@Composable
fun MonthCalendar(
    month: YearMonth,
    /**
     * 날짜별 대회 수. **목록이 아니라 건수다** — 셀은 개수만 쓰는데 목록을 넘기면
     * 받아온 페이지에 있는 대회만 점이 찍힌다(#85 리뷰 · API 명세 §3-2).
     */
    dailyCounts: Map<LocalDate, Int>,
    selectedDate: LocalDate?,
    onMonthChange: (Long) -> Unit,
    onDateSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MonthNavigator(month = month, onMonthChange = onMonthChange)
        Spacer(Modifier.height(8.dp))
        WeekdayHeader()
        Spacer(Modifier.height(4.dp))
        DayGrid(
            month = month,
            dailyCounts = dailyCounts,
            selectedDate = selectedDate,
            onDateSelect = onDateSelect,
        )
    }
}

@Composable
private fun MonthNavigator(
    month: YearMonth,
    onMonthChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = { onMonthChange(-1) }) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "이전 달")
        }
        Text(
            text = "${month.year}.${month.monthValue}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
        )
        IconButton(onClick = { onMonthChange(1) }) {
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "다음 달")
        }
    }
}

@Composable
private fun WeekdayHeader(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        DOW_KO.forEachIndexed { index, label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = when (index) {
                    0 -> SundayRed
                    6 -> SaturdayBlue
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayGrid(
    month: YearMonth,
    dailyCounts: Map<LocalDate, Int>,
    selectedDate: LocalDate?,
    onDateSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = today()
    // 일요일 시작으로 맞춘다. DayOfWeek는 월=1이므로 일요일이 0이 되도록 보정.
    val leadingBlanks = month.atDay(1).dayOfWeek.value % DayOfWeek.SUNDAY.value
    val cells: List<LocalDate?> =
        List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }

    Column(modifier = modifier.fillMaxWidth()) {
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            DayCell(
                                date = date,
                                raceCount = dailyCounts[date] ?: 0,
                                isSelected = date == selectedDate,
                                isToday = date == today,
                                onClick = { onDateSelect(date) },
                            )
                        }
                    }
                }
                // 마지막 주가 7칸이 안 되면 남은 칸을 채워 정렬을 유지한다.
                repeat(7 - week.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 날짜 동그라미 지름. */
private val DAY_CIRCLE = 28.dp

/** 동그라미와 건수 줄 사이. */
private val DAY_GAP = 3.dp

/** 칸 위아래 여백 합. */
private val DAY_PADDING = 7.dp

@Composable
private fun DayCell(
    date: LocalDate,
    raceCount: Int,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasRace = raceCount > 0
    // 건수 줄의 높이를 **타이포그래피에서 끌어온다.** `dp` 상수로 박으면 두 가지가 샌다 —
    // 글꼴 크기를 키운 기기에서 `sp` 가 커져 다시 잘리고, `Type.kt` 의 `labelSmall` 을
    // 고쳐도 여기가 안 따라온다(#128 리뷰).
    val countLineHeight = with(LocalDensity.current) {
        MaterialTheme.typography.labelSmall.lineHeight.toDp()
    }
    Column(
        modifier = modifier
            // 원(28) + 사이(3) + 건수 줄 + 상하 여백(7). 모든 칸이 같은 값을 쓰므로
            // 글꼴이 커져도 날짜 숫자는 가로로 나란히 선다.
            .height(DAY_CIRCLE + DAY_GAP + countLineHeight + DAY_PADDING)
            // 대회가 없는 날은 선택할 수 없다.
            .clickable(enabled = hasRace, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    CircleShape,
                )
                .then(
                    if (isToday && !isSelected) {
                        Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (hasRace) FontWeight.ExtraBold else FontWeight.Normal,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    hasRace -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.outline
                },
            )
        }
        Spacer(Modifier.height(DAY_GAP))
        Row(
            // 점 크기(5dp) 기준인 6dp 로 잡았더니 건수 숫자가 아래로 잘렸다 — `labelSmall`
            // 줄높이가 그 상자를 넘는다. **칸마다 높이가 달라지면** 세로 가운데 정렬 때문에
            // 날짜 숫자가 들쭉날쭉해지므로, `heightIn` 이 아니라 모든 칸이 같은 값을 쓴다.
            modifier = Modifier.height(countLineHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (hasRace && !isSelected) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                // 2건 이상이면 건수를 함께 보여준다.
                if (raceCount > 1) {
                    Text(
                        text = raceCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
