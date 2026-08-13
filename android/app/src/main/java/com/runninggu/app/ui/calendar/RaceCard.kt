package com.runninggu.app.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.runninggu.app.ui.theme.Ink2
import com.runninggu.app.ui.theme.Ink4
import com.runninggu.app.ui.theme.Ink5
import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.ui.model.RegistrationStatus
import com.runninggu.app.ui.model.registrationStatus
import java.time.format.TextStyle
import java.util.Locale

private val MONTHS_EN = listOf(
    "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
)

/**
 * 대회 카드. (SPEC §4.5)
 *
 * 날짜 컬럼(영문 월/일/요일) · 대회명 + 하트 · 지역·장소 · 종목 태그(첫 태그 강조) ·
 * 접수 상태 칩 + 출처. 마감이면 카드를 흐리게, featured면 강조한다.
 */
@Composable
fun RaceCard(
    race: RaceSummary,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    modifier: Modifier = Modifier,
    featured: Boolean = false,
) {
    val status = race.registrationStatus()
    val closed = status != RegistrationStatus.OPEN

    // 목업 .racerow — 흰 바탕에 옅은 테두리로 구분하고, featured만 파란 테두리로 강조한다.
    // 마감은 카드 전체를 흐리게 하지 않고 텍스트 색만 낮춘다 (.racerow.closed).
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp), // --r-card
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (featured) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        elevation = CardDefaults.cardElevation(defaultElevation = if (featured) 6.dp else 0.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            DateColumn(race = race, featured = featured)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = race.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (closed) Ink2 else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onFavoriteToggle,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = if (isFavorite) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Filled.FavoriteBorder
                            },
                            contentDescription = if (isFavorite) "찜 해제" else "찜하기",
                            tint = if (isFavorite) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Text(
                    text = "${race.region} · ${race.venue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (closed) Ink5 else Ink4,
                )

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    race.eventTypes.forEachIndexed { index, event ->
                        EventTag(label = event, highlighted = index == 0)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(status = status)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = buildString {
                            append(race.source)
                            race.checked?.let {
                                append(" · 확인 %02d.%02d".format(it.monthValue, it.dayOfMonth))
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DateColumn(race: RaceSummary, featured: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.width(44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = MONTHS_EN[race.date.monthValue - 1],
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (featured) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = "%02d".format(race.date.dayOfMonth),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = race.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EventTag(label: String, highlighted: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
        color = if (highlighted) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .background(
                if (highlighted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
                CircleShape,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun StatusChip(status: RegistrationStatus, modifier: Modifier = Modifier) {
    val open = status == RegistrationStatus.OPEN
    Text(
        text = status.label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (open) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .background(
                if (open) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                CircleShape,
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
