package com.runninggu.app.ui.home

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import com.runninggu.app.R
import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.ui.model.FestivalSummary
import com.runninggu.app.ui.model.RaceSummary
import com.runninggu.app.ui.model.dDayLabel
import com.runninggu.app.ui.model.registrationStatus
import com.runninggu.app.ui.theme.Archivo
import com.runninggu.app.ui.theme.Deep
import com.runninggu.app.ui.theme.DeepT1
import com.runninggu.app.ui.theme.DeepT2
import com.runninggu.app.ui.theme.DeepT3
import com.runninggu.app.ui.theme.Ink
import com.runninggu.app.ui.theme.Lime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.delay

private val HERO_HEIGHT = 472.dp
private val HERO_PADDING = 22.dp
private val MONTH_DAY = DateTimeFormatter.ofPattern("MM.dd")

/** 사진이 바뀔 때 번지는 시간. 막도 같은 박자로 짙어진다. */
private const val PHOTO_FADE_MILLIS = 600

/**
 * 사진을 다음 장으로 넘기는 간격. 한 장이 읽힐 만큼 두되, 여섯 장이 한 바퀴 도는 데
 * 1분이 안 걸리는 선이다.
 */
private const val PHOTO_INTERVAL_MILLIS = 6_000L

/**
 * S1 홈 히어로. (목업 v2 `.hero` — 몰입 다크 레지스터 · SPEC §4.4 히어로 배경)
 *
 * 배경 위에 로고·대표 대회를 얹는다. 검색은 히어로 아래 흰 카드로 내려갔다(`HomeScreen` 의
 * 검색 카드 · 2026-09-14). 배경은 두 겹이다 —
 *
 * - **바탕은 늘 지형 그림([HeroTerrain])이다.** 사진이 아직 안 왔거나, 없거나, 못 받았을 때
 *   그대로 보인다. #247 에서 "지우지 말고 사진이 없을 때의 배경으로 두자" 고 정한 폴백이다
 * - **[photos] 가 있으면 그 사진들을 돌려 보여준다([HeroSlideshow]).** 축제 추천(§4.4-4)에서
 *   고른 KTO 사진이라 따로 부르는 API 가 없고, 장식이라 탭 동작도 없다. 사진 위에는
 *   [HeroScrim] 을 덮어 로고·대표 대회 글자를 살린다
 *
 * 처음 #247 은 이 자리에 축제 **캐러셀**을 깔려다 물러났다 — 위에 얹힌 대표 대회가 탭을
 * 먼저 가져가서다([FestivalCarousel] KDoc). 사진은 누를 것이 없어 그 다툼이 없다.
 *
 * 대회 `imageUrl` 은 쓰지 않는다. 원천(마라톤GO)이 저장한 것이 대회 **공식 홈페이지 전체
 * 스크린샷**(세로 1366×1500~5400)이라, 배경으로 잘라 쓰면 표와 메뉴바가 보인다.
 *
 * 상태바 뒤까지 배경이 깔리도록 앱 셸이 상단 인셋을 먹지 않으며,
 * 내용만 [statusBarsPadding]으로 밀어낸다.
 */
@Composable
fun HomeHero(
    race: RaceSummary?,
    photos: List<FestivalSummary>,
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

    // 지금 화면에 떠 있는 사진. 슬라이드쇼가 넘겨 준다 — null 이면 아직 한 장도 안 떴다.
    // 크레딧은 **떠 있는 장**의 것이어야 한다. URL 만 보고 적으면 못 받은 사진에 이름을 단다
    var shownPhoto by remember(photos) { mutableStateOf<FestivalSummary?>(null) }

    Box(modifier = modifier.fillMaxWidth().height(HERO_HEIGHT)) {
        HeroTerrain(Modifier.fillMaxSize())
        if (photos.isNotEmpty()) {
            HeroSlideshow(
                photos = photos,
                onShownChange = { shownPhoto = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            BrandRow(photoCredit = shownPhoto?.name)
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

/**
 * 축제 사진 슬라이드쇼 + 어두운 막. (SPEC §4.4 히어로 배경 · NFR-7)
 *
 * **시작 장은 무작위다.** 늘 같은 첫 장으로 열리면 "바뀌는 배경" 이 아니라 "고정된 배경" 으로
 * 읽힌다 — 한 장만 깔았던 첫 판이 그랬다(2026-09-14). 그 뒤로는 [PHOTO_INTERVAL_MILLIS] 마다
 * 다음 장으로 넘긴다. 진행 중인 축제가 앞에 오도록 [HomeUiState.heroPhotos] 가 정렬해 준다.
 *
 * **다음 장은 먼저 받아 놓고 바꾼다.** 보이는 장을 바로 갈아 끼우면 새 사진이 내려오는 동안
 * 아래 지형 그림이 비친다. 그래서 다음 장을 안 보이게(`alpha = 0`) 같은 크기로 미리 받고,
 * 성공했을 때만 넘어간다 — 같은 크기로 받아야 메모리 캐시 키가 같아서 보이는 쪽이 바로 뜬다.
 * 못 받은 장은 건너뛴다. 한 바퀴를 다 실패하면 멈춘다 — 안 그러면 네트워크가 끊긴 채로 헛돈다.
 *
 * **바꿀 때 앞 장을 지우지 않는다.** 두 장을 동시에 반투명으로 섞으면(`Crossfade`) 중간에 둘 다
 * 절반이라 아래 지형 그림의 라임 호가 비쳤다(에뮬레이터 확인 · 2026-09-14). 그래서 앞 장은
 * 불투명하게 두고 **새 장만 위에서 불투명해진다.** 다 뜨면 앞 장을 치운다. [key] 로 묶어 두어야
 * 새 장이 "위" 에서 "아래" 로 자리를 옮겨도 같은 인스턴스라 다시 읽지 않는다.
 *
 * 막은 첫 장이 **떴을 때만** 같은 박자로 짙어진다. 안 뜬 사진 위에 막을 덮으면 폴백 지형만
 * 괜히 어두워진다.
 */
@Composable
private fun HeroSlideshow(
    photos: List<FestivalSummary>,
    onShownChange: (FestivalSummary?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 아래에 깔린 장 / 위에서 떠오르는 장(= 지금 보이는 장) / 받는 중인 장 / 연달아 실패한 수
    var under by remember(photos) { mutableStateOf<Int?>(null) }
    var shown by remember(photos) { mutableStateOf<Int?>(null) }
    var pending by remember(photos) { mutableStateOf<Int?>(Random.nextInt(photos.size)) }
    var failures by remember(photos) { mutableStateOf(0) }
    val shownAlpha = remember { Animatable(1f) }

    // 새 장이 오면 위에서 불투명해지고, 다 뜨면 아래 장을 치운다
    LaunchedEffect(shown) {
        if (shown == null) return@LaunchedEffect
        shownAlpha.snapTo(0f)
        shownAlpha.animateTo(1f, tween(PHOTO_FADE_MILLIS))
        under = null
    }

    // 시계. 한 장이 떠 있고 받는 중인 장이 없을 때만 다음 장을 예약한다. 한 장뿐이면 안 돈다
    LaunchedEffect(photos, shown, pending) {
        val current = shown
        if (current != null && pending == null && photos.size > 1) {
            delay(PHOTO_INTERVAL_MILLIS)
            pending = (current + 1) % photos.size
        }
    }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (shown != null) 1f else 0f,
        animationSpec = tween(PHOTO_FADE_MILLIS),
        label = "heroScrim",
    )

    Box(modifier) {
        // 아래 장 → 위 장 순서로 그린다. 같은 장을 두 번 그리지 않는다(key 중복 방지)
        for (index in listOfNotNull(under.takeIf { it != shown }, shown)) {
            key(index) {
                AsyncImage(
                    model = photos[index].imageUrl,
                    // 장식이다 — 어느 축제 사진인지는 로고 옆 크레딧 글자가 읽어 준다
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        // 그리기 단계에서만 읽는다 — 매 프레임 재구성하지 않으려고
                        .graphicsLayer { alpha = if (index == shown) shownAlpha.value else 1f },
                )
            }
        }
        HeroScrim(Modifier.fillMaxSize().alpha(scrimAlpha))

        // 다음 장 미리 받기. 그리지는 않는다
        pending?.let { next ->
            AsyncImage(
                model = photos[next].imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = {
                    under = shown
                    shown = next
                    pending = null
                    failures = 0
                    onShownChange(photos[next])
                },
                onError = {
                    failures += 1
                    pending = if (failures < photos.size) (next + 1) % photos.size else null
                },
                modifier = Modifier.fillMaxSize().alpha(0f),
            )
        }
    }
}

/**
 * 사진 위의 막. 위(상태바·로고·크레딧)와 아래(대표 대회·CTA)는 짙고 가운데는 사진이 보이게
 * 둔다. 아래쪽은 [HeroTerrain] 의 veil 과 같은 역할이다 — 라임 D-day 와 흰 대회명이 어떤
 * 사진 위에서도 읽혀야 한다.
 */
@Composable
private fun HeroScrim(modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.verticalGradient(
                // 위쪽엔 로고·크레딧이 앉는다 — KTO 사진은 포스터형이 많아 큰 제목이
                // 이 자리로 비치므로 다른 구간보다 짙게 누른다 (에뮬레이터 확인 · 2026-09-14)
                0f to Deep.copy(alpha = 0.82f),
                0.22f to Deep.copy(alpha = 0.60f),
                0.36f to Deep.copy(alpha = 0.28f),
                0.50f to Deep.copy(alpha = 0.34f),
                0.64f to Deep.copy(alpha = 0.84f),
                1f to Deep.copy(alpha = 0.97f),
            ),
        ),
    )
}

/**
 * 라임 로고 타일 + 워드마크. (목업 .hero .brandrow)
 *
 * [photoCredit] 이 있으면 오른쪽에 "사진 · {축제명} · 한국관광공사" 를 적는다 — KTO 이미지는
 * 크레딧이 조건이다(NFR-7). 사진이 떠 있을 때만 넘어온다.
 *
 * 로고 아래 반투명 검색 필드가 있던 때는 이 줄이 검색 위에 얹혔는데, 검색이 히어로 아래
 * 카드로 내려가 지금은 이 줄과 대표 대회 사이가 전부 사진이다.
 */
@Composable
private fun BrandRow(photoCredit: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = HERO_PADDING, end = HERO_PADDING, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.app_icon),
            contentDescription = null,
            modifier = Modifier.size(34.dp),
        )
        Text(
            text = "런닝구",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.8).sp,
            color = DeepT1,
        )
        if (photoCredit != null) {
            Text(
                text = "사진 · $photoCredit · 한국관광공사",
                style = MaterialTheme.typography.labelSmall,
                color = DeepT2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
        }
    }
}

/**
 * 대표 대회 블록 — D-day·대회명·접수 칩·CTA. (목업 .hero .featured)
 *
 * **출처·확인일은 적지 않는다**(2026-09-14 · 이건모). 사진 위에 글자가 많아 답답했고,
 * 같은 값을 캘린더 카드(§4.5)와 상세(§4.6)가 보여 준다 — 출처가 사라지는 것이 아니라
 * 히어로에서만 빠진다(A3 는 S2·S3 대상).
 */
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
            Spacer(Modifier.weight(1f))
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

// 목업 .hero .field 의 반투명 배경. 접수 칩이 접수중이 아닐 때 바탕으로 쓴다.
private val Color_HeroField = androidx.compose.ui.graphics.Color(0xB8131A38)
