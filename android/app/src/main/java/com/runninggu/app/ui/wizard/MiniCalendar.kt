package com.runninggu.app.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.runninggu.app.ui.theme.SaturdayBlue
import com.runninggu.app.ui.theme.SundayRed
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val DOW_KO = listOf("일", "월", "화", "수", "목", "금", "토")

/**
 * 위저드 미니 캘린더. (SPEC §4.7)
 *
 * S2의 [com.runninggu.app.ui.calendar.MonthCalendar]와 목적이 다르다 —
 * 저쪽은 "대회 있는 날만" 고르는 탐색용이고, 이쪽은 여행 기간을 보여주고
 * 직접 선택일 때 아무 날이나 고르는 용도다. 그래서 합치지 않고 따로 둔다.
 *
 * 대회일은 채운 원, 여행 기간은 옅은 배경으로 구분한다.
 */
@Composable
fun MiniCalendar(
    month: YearMonth,
    raceDate: LocalDate,
    isInRange: (LocalDate) -> Boolean,
    selectable: Boolean,
    onDateTap: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "${month.year}.${month.monthValue}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        WeekdayHeader()
        Spacer(Modifier.height(4.dp))
        DayGrid(
            month = month,
            raceDate = raceDate,
            isInRange = isInRange,
            selectable = selectable,
            onDateTap = onDateTap,
        )
    }
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth()) {
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
    raceDate: LocalDate,
    isInRange: (LocalDate) -> Boolean,
    selectable: Boolean,
    onDateTap: (LocalDate) -> Unit,
) {
    // 일요일 시작. DayOfWeek는 월=1이므로 일요일이 0이 되도록 보정한다.
    val leading = month.atDay(1).dayOfWeek.value % DayOfWeek.SUNDAY.value
    val daysInMonth = month.lengthOfMonth()
    // 빈 칸 대신 앞뒤 달 날짜를 채운다. 대회가 월초·월말이면 여행 기간이 옆 달로
    // 넘어가는데, 빈 칸으로 두면 "3일"이라 해놓고 이틀만 칠해지는 꼴이 된다.
    val trailing = (7 - (leading + daysInMonth) % 7) % 7
    val firstCell = month.atDay(1).minusDays(leading.toLong())
    val cells: List<LocalDate> =
        List(leading + daysInMonth + trailing) { firstCell.plusDays(it.toLong()) }

    Column(Modifier.fillMaxWidth()) {
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        DayCell(
                            date = date,
                            isRaceDay = date == raceDate,
                            inRange = isInRange(date),
                            isOtherMonth = YearMonth.from(date) != month,
                            selectable = selectable,
                            onClick = { onDateTap(date) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isRaceDay: Boolean,
    inRange: Boolean,
    isOtherMonth: Boolean,
    selectable: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(enabled = selectable, onClick = onClick)
            // 기간은 옅은 배경, 대회일은 그 위에 채운 원을 얹는다.
            .background(
                color = if (inRange && !isRaceDay) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(6.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    color = if (isRaceDay) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isRaceDay || inRange) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isRaceDay -> MaterialTheme.colorScheme.onPrimary
                    inRange -> MaterialTheme.colorScheme.onPrimaryContainer
                    // 옆 달 날짜는 흐리게 — 있다는 건 보이되 이달과 구분된다.
                    isOtherMonth -> MaterialTheme.colorScheme.outlineVariant
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** 미니 캘린더 범례 — 색이 뭘 뜻하는지 알려준다. (SPEC §4.7) */
@Composable
fun MiniCalendarLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LegendItem(color = MaterialTheme.colorScheme.primary, label = "대회일", circle = true)
        LegendItem(color = MaterialTheme.colorScheme.primaryContainer, label = "여행 기간")
    }
}

@Composable
private fun LegendItem(color: Color, label: String, circle: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(12.dp)
                .background(color, if (circle) CircleShape else RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
