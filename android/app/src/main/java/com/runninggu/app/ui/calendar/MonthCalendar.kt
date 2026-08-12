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
import androidx.compose.ui.unit.dp
import com.runninggu.app.ui.model.RaceSummary
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
    racesByDate: Map<LocalDate, List<RaceSummary>>,
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
            racesByDate = racesByDate,
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
    racesByDate: Map<LocalDate, List<RaceSummary>>,
    selectedDate: LocalDate?,
    onDateSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
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
                                races = racesByDate[date].orEmpty(),
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

@Composable
private fun DayCell(
    date: LocalDate,
    races: List<RaceSummary>,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasRace = races.isNotEmpty()
    Column(
        modifier = modifier
            .height(46.dp)
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
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier.height(6.dp),
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
                if (races.size > 1) {
                    Text(
                        text = races.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
