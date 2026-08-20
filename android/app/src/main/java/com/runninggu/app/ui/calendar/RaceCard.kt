package com.runninggu.app.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import com.runninggu.app.ui.common.ElevationLine
import com.runninggu.app.ui.theme.Ink3
import com.runninggu.app.ui.theme.NumeralLabel
import com.runninggu.app.ui.theme.NumeralLarge
import com.runninggu.app.ui.theme.Orange
import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.ui.model.isDimmed
import com.runninggu.app.ui.model.registrationStatus
import androidx.compose.ui.draw.alpha
import java.time.format.TextStyle
import java.util.Locale

private val MONTHS_EN = listOf(
    "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
)

/**
 * 대회 카드. (SPEC §4.5)
 *
 * 날짜 컬럼(영문 월/일/요일) · 대회명 + 하트 · 지역·장소 · 종목 태그(첫 태그 강조) ·
 * 접수 상태 칩 + 출처. featured면 강조한다.
 *
 * **흐림이 두 종류가 아니라는 점을 헷갈리지 말 것.**
 * - 접수 마감([closed]) — 카드를 흐리게 하지 않고 **텍스트 색만** 낮춘다. 아직 열릴 수 있다
 * - 지난·비활성 대회([RaceSummary.isDimmed]) — **카드 전체**를 흐리게 한다. 끝난 대회다
 *
 * 흐림 판정을 밖에서 받지 않고 여기서 하는 이유는, 카드를 쓰는 곳이 늘어날 때마다
 * 호출부가 잊으면 조용히 규칙이 어긋나기 때문이다. 판정 자체는 클라 몫이다(§7-C 🔒).
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
    // 비활성 대회도 마감과 같은 텍스트 색을 쓴다. 흐림만 걸고 날짜·고도선을 "접수중" 파랑으로
    // 두면, 같은 목록의 지난 대회보다 살아 있는 것처럼 읽힌다 (결정-46).
    val closed = status != RegistrationStatus.OPEN || !race.active
    val dimmed = race.isDimmed()

    // 목업 .racerow — 흰 바탕에 옅은 테두리로 구분하고, featured만 파란 테두리로 강조한다.
    // 마감은 카드 전체를 흐리게 하지 않고 텍스트 색만 낮춘다 (.racerow.closed).
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (dimmed) DIMMED_ALPHA else 1f),
        shape = RoundedCornerShape(20.dp), // --r-card
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (featured) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        elevation = CardDefaults.cardElevation(defaultElevation = if (featured) 6.dp else 0.dp),
    ) {
        // IntrinsicSize.Min — 세로 실선(vline)이 fillMaxHeight로 늘어나려면
        // Row가 자기 높이를 알아야 한다. 없으면 실선 높이가 0이 되어 안 보인다.
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 15.dp)
                .height(IntrinsicSize.Min),
        ) {
            DateColumn(race = race, featured = featured, closed = closed)
            Spacer(Modifier.width(14.dp))
            // 목업 .racerow .vline — 날짜 컬럼과 본문을 세로 실선으로 가른다.
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
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
                            // 목업 하트 채움은 포인트 오렌지다.
                            tint = if (isFavorite) Orange else Ink5,
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

                // 코스 고도 스트립. (목업 .racerow .elevline)
                Spacer(Modifier.height(9.dp))
                ElevationLine(
                    seed = race.id.hashCode(),
                    closed = closed,
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 원천에서 사라진 대회는 접수 상태를 말할 수 없다. "접수중" 을 그대로
                    // 두면 신청할 수 있다는 뜻이 되므로 자리를 안내로 바꾼다 (결정-46).
                    if (race.active) {
                        StatusChip(status = status, race = race)
                    } else {
                        EndedChip()
                    }
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
private fun DateColumn(
    race: RaceSummary,
    featured: Boolean,
    closed: Boolean,
    modifier: Modifier = Modifier,
) {
    // 목업 .racerow .datecol — 월은 파랑 mono, 일은 큰 mono, 요일은 옅은 회색.
    Column(
        modifier = modifier.width(46.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = MONTHS_EN[race.date.monthValue - 1],
            style = NumeralLabel,
            color = when {
                closed -> Ink5
                else -> MaterialTheme.colorScheme.primary
            },
        )
        Text(
            text = "%02d".format(race.date.dayOfMonth),
            style = NumeralLarge,
            color = if (closed) Ink3 else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = race.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN),
            style = MaterialTheme.typography.labelSmall,
            color = Ink4,
        )
    }
}

@Composable
private fun EventTag(label: String, highlighted: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        // 목업 태그 — 대표 종목은 연파랑 바탕에 파란 글씨, 나머지는 옅은 회색 바탕이다.
        color = if (highlighted) MaterialTheme.colorScheme.primary else Ink2,
        modifier = modifier
            .background(
                if (highlighted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                RoundedCornerShape(9.dp),
            )
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun StatusChip(
    status: RegistrationStatus,
    race: RaceSummary,
    modifier: Modifier = Modifier,
) {
    val open = status == RegistrationStatus.OPEN
    // 목업은 마감일을 칩 안에 넣어 "접수중 · ~08.05" 한 덩어리로 보여준다.
    val label = if (open && race.regEnd != null) {
        "${status.label} · ~%02d.%02d".format(race.regEnd.monthValue, race.regEnd.dayOfMonth)
    } else {
        status.label
    }
    Text(
        text = label,
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

/**
 * 원천에서 사라진 대회 표시. (SPEC §4.13 · 결정-46)
 *
 * 접수 상태 칩과 같은 자리·같은 모양이라 목록이 들쭉날쭉해지지 않는다. 문구는
 * 명세 그대로 **"정보 제공 종료"** 다 — 대회가 취소됐다는 뜻이 아니라 우리가 더는
 * 소식을 받지 못한다는 뜻이라, 단정하는 표현을 쓰지 않는다.
 */
@Composable
private fun EndedChip(modifier: Modifier = Modifier) {
    Text(
        text = "정보 제공 종료",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * 지난·비활성 대회 카드의 투명도. (SPEC §4.13)
 *
 * 읽을 수는 있지만 살아 있는 카드와 확실히 구분되는 정도다. 원래 `MyScreen` 안에
 * 사인화돼 있던 값을 카드 쪽으로 옮겼다 — 흐림 규칙이 카드와 함께 있어야 한다.
 */
private const val DIMMED_ALPHA = 0.45f
