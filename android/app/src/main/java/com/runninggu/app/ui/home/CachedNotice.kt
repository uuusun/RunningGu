package com.runninggu.app.ui.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.runninggu.app.domain.KST
import java.time.Instant
import java.time.format.DateTimeFormatter

private val CACHED_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd HH:mm")

/**
 * 문구의 시각 부분. **화면 밖에서 고정할 수 있게 떼어 둔다** — Compose 안에 두면
 * KST 변환이 맞는지 단위 테스트로 잡을 수 없다.
 */
internal fun cachedAtLabel(at: Instant): String = CACHED_AT.format(at.atZone(KST))

/**
 * 캐시로 그린 영역임을 알리는 한 줄. (매핑표 171행 · SPEC §6.1 · 이슈 #276)
 *
 * ## 왜 필요한가
 *
 * **캐시가 없던 때는 오류가 신호였다.** 안 되면 안 된다고 나왔으니 사용자가 알았다.
 * 폴백이 들어오면 화면이 **조용히** 낡은 것을 그린다 — 개선이 맞지만 그 대가로
 * 신호가 사라진다. 온라인 화면과 픽셀 단위로 같아서 지금 보는 것이 언제 것인지
 * 알 방법이 없다(#275 기기 확인).
 *
 * 대회는 그게 특히 아프다. **접수 마감이 걸려 있다.** 캐시된 `마감 D-0` 을 보고
 * 신청하러 갔는데 이미 끝났을 수 있다. 축제나 코스와 달리 시간이 지나면 틀린 정보가 된다.
 *
 * ## 왜 "기준" 인가
 *
 * `오프라인` 만 적으면 언제 것인지 모르고, 시각만 적으면 왜 낡았는지 모른다. 둘 다 적는다.
 * 시각은 **KST 로 옮겨** 보여준다 — 저장은 UTC 지만 사용자가 읽는 시간은 KST 다(§6.6).
 */
@Composable
fun CachedNotice(cachedAt: Instant, modifier: Modifier = Modifier) {
    Text(
        text = "오프라인 · ${cachedAtLabel(cachedAt)} 기준",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 2.dp, bottom = 6.dp),
    )
}
