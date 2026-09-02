package com.runninggu.app.ui.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.runninggu.app.ui.model.FestivalSummary

/**
 * 홈 축제 캐러셀. 좌우로 넘기고, 궁금한 것을 누르면 그 카드만 커진다. (이슈 #247 · SPEC §4.4-4)
 *
 * > 사용자 위치·위치 권한을 사용하지 않고 조회 월의 전국 진행 중·임박 축제 **캐러셀**을
 * > 보여준다 🔒확정
 *
 * ## 왜 히어로 배경이 아니라 여기인가
 *
 * 처음에는 히어로의 지형 그림([HeroTerrain]) 자리에 깔았다. **자리 다툼으로 물러났다.**
 * 히어로 472dp 에는 로고·검색·대표 대회·CTA 가 이미 다 들어 있어서,
 *
 * - 카드를 누르려 하면 위에 얹힌 **대표 대회가 탭을 먼저 가져간다**
 * - 위 글씨를 읽히게 하려면 어두운 막을 덮어야 하는데 그러면 카드도 같이 묻힌다
 *
 * 튜닝으로 좁혀지는 문제가 아니라 한 자리를 둘이 쓰려던 것이었다. 반면 여기는 위에
 * 겹치는 것이 없어 탭·스크롤·확대가 다 된다. **§4.4-4 가 애초에 캐러셀로 정해 둔 자리다.**
 *
 * ## 이미지는 아직 안 붙는다
 *
 * [FestivalSummary.imageUrl] 을 받지만 지금은 그리지 않는다. Coil 이 #247 에서 승인됐는데
 * KTO `firstimage` 가 `http://` 라 `targetSdk 36` 의 cleartext 차단에 걸린다 — 서버가
 * https 로 정규화해 주는 것이 선행 조건이다.
 *
 * 그때까지도 화면은 성립해야 한다. 명세 §4-1 이 *"`imageUrl` 은 nullable 이며 없으면 앱이
 * 기본 placeholder 를 표시한다"* 고 정해 두었으므로 **없을 때의 모습은 임시가 아니라 계약**이다.
 */
@Composable
fun FestivalCarousel(
    festivals: List<FestivalSummary>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp),
) {
    // 펼친 카드. 한 번 더 누르면 접힌다 — 닫을 다른 방법을 따로 만들지 않아도 된다
    var expandedId by remember { mutableStateOf<String?>(null) }

    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(festivals, key = { it.id }) { festival ->
            FestivalCard(
                festival = festival,
                expanded = festival.id == expandedId,
                onClick = {
                    expandedId = if (festival.id == expandedId) null else festival.id
                },
            )
        }
    }
}

/**
 * 축제 카드 한 장.
 *
 * **커지는 것은 사진뿐이다.** 글자 크기는 그대로 두고 이미지 자리만 넓힌다 — 확대해서
 * 보고 싶은 것이 사진이지 이름이 아니고, 글자가 같이 커지면 옆 카드와 어긋나 보인다.
 * 응답에 있는 것이 일곱 필드뿐이라 펼쳐도 더 보여줄 정보가 없다(D-05).
 */
@Composable
private fun FestivalCard(
    festival: FestivalSummary,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val width by animateDpAsState(
        targetValue = if (expanded) EXPANDED_WIDTH else COLLAPSED_WIDTH,
        label = "festivalCardWidth",
    )
    val imageHeight by animateDpAsState(
        targetValue = if (expanded) EXPANDED_IMAGE else COLLAPSED_IMAGE,
        label = "festivalImageHeight",
    )

    Card(
        modifier = modifier
            .width(width)
            .clickable(onClick = onClick)
            // 사진에 대체 텍스트가 없으므로 카드 전체를 하나로 읽어 준다
            .semantics {
                contentDescription = buildString {
                    append(festival.name)
                    if (festival.isOngoing) append(", 진행 중")
                    append(", ").append(festival.period).append(' ').append(festival.region)
                    append(if (expanded) ", 펼침" else ", 눌러서 크게 보기")
                }
            },
        shape = RoundedCornerShape(16.dp),
        // 목업 .railcard — 흰 바탕 + 옅은 테두리 + 얕은 그림자.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        FestivalImage(
            festival = festival,
            modifier = Modifier
                .fillMaxWidth()
                .height(imageHeight),
        )
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = festival.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                // 폭이 변하는 카드라 줄 수를 고정한다 — 안 그러면 펼칠 때 카드 높이가
                // 두 번 튄다(사진이 커지고, 제목이 한 줄로 줄면서 또)
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer6()
            Text(
                text = "${festival.period} · ${festival.region}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 사진 자리. KTO `firstimage` 를 Coil 로 받는다. (명세 §4-1)
 *
 * **`imageUrl` 이 없거나 못 받으면 placeholder 가 남는다.** 명세가 nullable 로 두고
 * *"없으면 앱이 기본 placeholder 를 표시한다"* 고 정해 둔 계약이다. 그래서 사진을
 * [Box] 위에 얹기만 하고 바탕은 늘 깔아 둔다 — 로딩 중에도 회색 구멍이 안 생긴다.
 *
 * 축제마다 바탕색이 다른 것은, 사진이 없는 카드가 여러 장일 때 한 덩어리로 보이지 않게
 * 하려는 것이다. `id` 해시로 고르므로 같은 축제는 늘 같은 색이다.
 *
 * **`crossfade` 를 켜지 않는다.** 캐러셀을 넘길 때마다 카드가 깜빡이면 스크롤이 더
 * 어수선해 보인다. 캐시에서 바로 나오는 경우가 대부분이라 얻는 것도 적다.
 */
@Composable
private fun FestivalImage(festival: FestivalSummary, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(placeholderBrush(festival.id)),
    ) {
        if (festival.imageUrl != null) {
            AsyncImage(
                model = festival.imageUrl,
                // 사진 자체는 장식이다 — 축제 이름·기간을 카드가 이미 읽어 준다
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        if (festival.isOngoing) {
            Text(
                text = "진행 중",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

private fun placeholderBrush(id: String): Brush {
    val tops = listOf(
        Color(0xFFBFD0F5),
        Color(0xFFCFD9EF),
        Color(0xFFC6DCE6),
        Color(0xFFD5CEE8),
    )
    val top = tops[(id.hashCode().and(Int.MAX_VALUE)) % tops.size]
    return Brush.verticalGradient(listOf(top, Color(0xFFEDF1F8)))
}

@Composable
private fun Spacer6() = Box(Modifier.height(6.dp))

/** 접힌 카드 폭. 기존 축제 카드와 같은 값이라 다른 레일과 리듬이 맞는다. */
private val COLLAPSED_WIDTH: Dp = 200.dp

/** 펼친 카드 폭. 다음 카드가 살짝 남아 더 있다는 것이 보이는 선. */
private val EXPANDED_WIDTH: Dp = 300.dp

private val COLLAPSED_IMAGE: Dp = 116.dp
private val EXPANDED_IMAGE: Dp = 186.dp
