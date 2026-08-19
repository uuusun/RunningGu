package com.runninggu.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.ui.model.dDayLabel
import com.runninggu.app.ui.model.registrationStatus
import com.runninggu.app.ui.theme.Archivo
import com.runninggu.app.ui.theme.DeepT1
import com.runninggu.app.ui.theme.DeepT2
import com.runninggu.app.ui.theme.DeepT3
import com.runninggu.app.ui.theme.Ink
import com.runninggu.app.ui.theme.Lime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val HERO_HEIGHT = 472.dp
private val HERO_PADDING = 22.dp
private val MONTH_DAY = DateTimeFormatter.ofPattern("MM.dd")

/**
 * S1 홈 히어로. (목업 v2 `.hero` — 몰입 다크 레지스터)
 *
 * 지형 배경([HeroTerrain]) 위에 로고·검색·대표 대회를 얹는다.
 * 상태바 뒤까지 배경이 깔리도록 앱 셸이 상단 인셋을 먹지 않으며,
 * 내용만 [statusBarsPadding]으로 밀어낸다.
 */
@Composable
fun HomeHero(
    race: RaceSummary?,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRaceClick: () -> Unit,
    onStartWizard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 히어로가 상태바 뒤로 깔리므로 시계·배터리 아이콘을 밝게 바꾼다.
    // 홈을 벗어나면 원래대로 되돌린다.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { previous?.let { controller.isAppearanceLightStatusBars = it } }
    }

    Box(modifier = modifier.fillMaxWidth().height(HERO_HEIGHT)) {
        HeroTerrain(Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            BrandRow()
            Spacer(Modifier.height(12.dp))
            HeroSearchField(query = query, onQueryChange = onQueryChange, onSearch = onSearch)
            Spacer(Modifier.weight(1f))
            if (race != null) {
                FeaturedRace(
                    race = race,
                    onClick = onRaceClick,
                    onStartWizard = onStartWizard,
                )
            }
        }
    }
}

/** 라임 로고 타일 + 워드마크. (목업 .hero .brandrow) */
@Composable
private fun BrandRow() {
    Row(
        modifier = Modifier.padding(start = HERO_PADDING, end = HERO_PADDING, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier.size(34.dp).background(Lime, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Place,
                contentDescription = null,
                tint = Ink,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = "런닝구",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.8).sp,
            color = DeepT1,
        )
    }
}

/** 반투명 다크 검색 필드. (목업 .hero .field) */
@Composable
private fun HeroSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = HERO_PADDING)
            .fillMaxWidth()
            .height(52.dp)
            .background(Color_HeroField, RoundedCornerShape(16.dp))
            .border(1.5.dp, Color_HeroFieldBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = DeepT2,
            modifier = Modifier.size(22.dp),
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = "대회·지역 검색",
                    style = MaterialTheme.typography.bodyLarge,
                    color = DeepT3,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyLarge.copy(color = DeepT1),
                ),
                cursorBrush = SolidColor(Lime),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 대표 대회 블록 — D-day·대회명·접수 칩·CTA. (목업 .hero .featured) */
@Composable
private fun FeaturedRace(
    race: RaceSummary,
    onClick: () -> Unit,
    onStartWizard: () -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = HERO_PADDING)
            .padding(bottom = 44.dp)
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = race.dDayLabel(),
                fontFamily = Archivo,
                fontSize = 52.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 50.sp,
                letterSpacing = (-2).sp,
                color = Lime,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = race.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 22.sp,
                    letterSpacing = (-0.5).sp,
                    color = DeepT1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(race.date.format(MONTH_DAY))
                        append(" ")
                        append(race.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN))
                        append(" ")
                        append(race.startTime)
                        append(" · ")
                        append(race.venue)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = DeepT2,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RegistrationPill(race)
            Spacer(Modifier.width(10.dp))
            Text(
                text = buildString {
                    append(race.source)
                    race.checked?.let { append(" · 확인 ${it.format(MONTH_DAY)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = DeepT3,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "대회 보기",
                style = MaterialTheme.typography.labelLarge,
                color = DeepT1,
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = DeepT1,
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        // 목업 .cta.lime — 다크 히어로 위 주 행동은 라임이다.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(Lime, RoundedCornerShape(16.dp))
                .clickable(onClick = onStartWizard),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "이 대회로 동선 만들기",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Ink,
            )
        }
    }
}

/** "접수중 · ~08.10" 한 덩어리 칩. (목업 .chip-open) */
@Composable
private fun RegistrationPill(race: RaceSummary) {
    val status = race.registrationStatus()
    val label = when (status) {
        RegistrationStatus.OPEN ->
            race.regEnd?.let { "접수중 · ~${it.format(MONTH_DAY)}" } ?: "접수중"

        else -> status.label
    }
    val open = status == RegistrationStatus.OPEN
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.ExtraBold,
        color = if (open) Ink else DeepT2,
        modifier = Modifier
            .background(
                color = if (open) Lime else Color_HeroField,
                shape = CircleShape,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

// 목업 .hero .field 의 반투명 배경·테두리.
private val Color_HeroField = androidx.compose.ui.graphics.Color(0xB8131A38)
private val Color_HeroFieldBorder = androidx.compose.ui.graphics.Color(0x38A9B4DC)
