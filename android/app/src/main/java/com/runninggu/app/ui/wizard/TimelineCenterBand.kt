package com.runninggu.app.ui.wizard

/**
 * 타임라인 **중앙 밴드** 판정. (SPEC §4.10 — "LazyList 스크롤 중앙 밴드(상하 45% 제외)에
 * 든 카드 자동 활성", 목업 IntersectionObserver 계약 승계)
 *
 * ## 왜 화면 밖으로 뺐나
 *
 * `LazyListState.layoutInfo` 를 그대로 쓰면 **기기가 있어야만 확인할 수 있는 코드**가 된다.
 * 계측 테스트는 CI 가 돌리지 않으므로(AGENTS 3장) 밴드 계산이 통째로 검증 밖에 남는다.
 * 좌표 몇 개만 받는 순수 함수로 두면 단위 테스트가 닿는다 — 화면은 `layoutInfo` 를 이
 * 자료형으로 옮기기만 한다.
 */
internal data class TimelineItemBounds(
    /** `LazyListScope` 에 준 key. 조회 타임라인 카드는 블록 id 다. */
    val key: Any?,
    /** 뷰포트 기준 item 시작 위치(px). */
    val offset: Int,
    /** item 높이(px). */
    val size: Int,
) {
    val center: Float get() = offset + size / 2f
    val end: Int get() = offset + size
}

/** 위아래로 잘라내는 비율. 남는 가운데 10% 가 밴드다. (SPEC §4.10) */
private const val BAND_MARGIN_RATIO = 0.45f

/**
 * 중앙 밴드에 든 카드의 블록 id. 없으면 `null`.
 *
 * [blockIds] 로 거르는 이유는 목록에 카드만 있는 게 아니기 때문이다 — 지도·요약·일자 머리글·
 * 안내·바닥글도 같은 `LazyColumn` 의 item 이라, 거르지 않으면 지도가 가운데 왔을 때
 * `"map"` 을 활성 블록으로 넘기게 된다.
 *
 * ## 두 단계로 고른다
 *
 * 1. **중심이 밴드 안에 든 카드.** 평소에는 여기서 정해진다
 * 2. 하나도 없으면 **뷰포트 중앙을 덮고 있는 카드.** 카드가 밴드보다 크면(사진이 붙은 긴
 *    카드가 화면의 절반을 넘는 경우) 중심이 밴드를 지나쳐 버려 1번이 비는데, 그때 화면
 *    한가운데를 차지하고 있는 것은 분명히 그 카드다. 여기서 포기하면 **긴 카드만 활성이
 *    안 되는** 이상한 규칙이 된다
 *
 * 둘 다 여럿이면 **뷰포트 중앙에 가장 가까운 것**을 고른다. 밴드는 좁아서 보통 하나지만,
 * 짧은 카드가 둘 걸치는 경우가 있다.
 */
internal fun centeredBlockId(
    viewportStart: Int,
    viewportEnd: Int,
    items: List<TimelineItemBounds>,
    blockIds: Set<String>,
): String? {
    val height = viewportEnd - viewportStart
    if (height <= 0) return null

    val cards = items.filter { it.key is String && it.key in blockIds }
    if (cards.isEmpty()) return null

    val margin = height * BAND_MARGIN_RATIO
    val bandStart = viewportStart + margin
    val bandEnd = viewportEnd - margin
    val viewportCenter = viewportStart + height / 2f

    val inBand = cards.filter { it.center in bandStart..bandEnd }
    val covering = cards.filter { it.offset <= viewportCenter && it.end >= viewportCenter }

    return (inBand.ifEmpty { covering })
        .minByOrNull { kotlin.math.abs(it.center - viewportCenter) }
        ?.key as String?
}
