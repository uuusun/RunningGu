package com.runninggu.app.domain

/**
 * 동선 편집 연산. (SPEC §5.7 · 원본 `reference-web/src/lib/runninggu/edits.js`)
 *
 * 전부 **불변 조작**이다 — 원본 리스트를 고치지 않고 새 리스트를 돌려준다.
 * 지도 핀·장소 수는 [dayPins] · [countPlaces] 로 매번 다시 계산한다.
 *
 * **원본에서 바꾼 것** — `docs/domain-logic-audit.md` A4
 * 원본에는 대회 블록을 막는 코드가 **없다.** 그래서 목업에서도 편집 모드의 🏁 스타트 블록이
 * 삭제·드래그됐다(대조표 B4 — 버그). 서버는 이 요청에 `409 SYSTEM_BLOCK_IMMUTABLE` 을
 * 돌려주기로 돼 있으므로, 여기서 먼저 거부한다.
 */
object ItineraryEdits {

    /**
     * 이 블록을 사용자가 바꿀 수 있는가. (SPEC §5.7)
     *
     * 화면은 이 값으로 **그립·교체·삭제 버튼을 아예 숨긴다.** 아래 연산들의 거부는
     * 그래도 새어 들어온 경우를 막는 안전망이지 정상 흐름이 아니다.
     */
    fun canEdit(block: ItineraryBlock): Boolean = block.blockType != BlockType.RACE

    /**
     * 블록 필드 수정(시간·제목·설명 등). 블록 id 는 유지된다. (SPEC §5.7 · §6.3)
     *
     * [transform] 이 무엇을 돌려주든 `id` · `blockType` · `systemManaged` 는 원래 값을 지킨다 —
     * 편집으로 USER 블록이 RACE 가 되거나 그 반대가 되면 안 된다.
     */
    fun updateBlock(
        days: List<ItineraryDay>,
        dayIndex: Int,
        blockId: String,
        transform: (ItineraryBlock) -> ItineraryBlock,
    ): List<ItineraryDay> = mapDay(days, dayIndex) { day ->
        day.copy(
            blocks = day.blocks.map { block ->
                if (block.id != blockId || !canEdit(block)) {
                    block
                } else {
                    transform(block).copy(
                        id = block.id,
                        blockType = block.blockType,
                        systemManaged = block.systemManaged,
                    )
                }
            },
        )
    }

    /** 블록 삭제. 대회 블록은 지워지지 않는다. (SPEC §5.7) */
    fun removeBlock(days: List<ItineraryDay>, dayIndex: Int, blockId: String): List<ItineraryDay> =
        mapDay(days, dayIndex) { day ->
            day.copy(blocks = day.blocks.filterNot { it.id == blockId && canEdit(it) })
        }

    /**
     * 장소 교체(후보 시트에서 고른 POI 로). 블록 id 는 유지된다. (SPEC §5.7)
     *
     * @param catKey 함께 바꿀 분류. null 이면 기존 분류를 둔다.
     */
    fun replacePlace(
        days: List<ItineraryDay>,
        dayIndex: Int,
        blockId: String,
        place: Poi,
        catKey: BlockCategory? = null,
    ): List<ItineraryDay> = updateBlock(days, dayIndex, blockId) { block ->
        block.copy(place = place, desc = place.desc, catKey = catKey ?: block.catKey)
    }

    /**
     * 블록 추가. 새 id 를 부여한다. (SPEC §5.7 · §6.3)
     *
     * @param atIndex 넣을 자리. null 이거나 범위를 넘으면 맨 뒤에 붙인다.
     */
    fun addBlock(
        days: List<ItineraryDay>,
        dayIndex: Int,
        block: ItineraryBlock,
        atIndex: Int? = null,
    ): List<ItineraryDay> {
        // 사용자가 추가하는 블록은 항상 USER 다. 대회 블록은 시스템만 만든다.
        val newBlock = block.copy(
            id = nextBlockId(days),
            blockType = BlockType.USER,
            systemManaged = false,
        )
        return mapDay(days, dayIndex) { day ->
            val blocks = day.blocks.toMutableList()
            val at = atIndex?.coerceIn(0, blocks.size) ?: blocks.size
            blocks.add(at, newBlock)
            day.copy(blocks = blocks)
        }
    }

    /**
     * 순서 변경. **같은 일자 안에서만** 옮긴다. (SPEC §5.7 · API 명세 §5-10)
     *
     * 대회 블록 자체를 옮길 수 없고, **다른 블록이 대회 블록을 넘어갈 수도 없다.**
     *
     * 계약이 그렇게 정해져 있다 — `PUT .../blocks/order` 는 **대회 블록의 고정 위치를
     * 넘나드는 요청에 `409 SYSTEM_BLOCK_IMMUTABLE`** 을 준다(API 명세 §5-10). 여기서
     * 먼저 막는 것은 서버에 가서 거부당하기 전에 화면이 애초에 그 상태를 못 만들게
     * 하려는 것이다 — [canEdit] 이 버튼을 숨기는 것과 같은 층위의 선제 차단이다.
     *
     * 예전에는 "블록을 바꾸는 게 아니라 이웃이 움직이는 것" 이라며 넘는 것을 허용했다.
     * 저장 계약이 서기 전(#51)이라 검증할 대상이 없었을 뿐이고, 계약이 선 지금은 틀렸다.
     *
     * 범위를 벗어난 인덱스는 조용히 무시한다(원본과 같다).
     */
    fun moveBlock(days: List<ItineraryDay>, dayIndex: Int, from: Int, to: Int): List<ItineraryDay> =
        mapDay(days, dayIndex) { day ->
            if (from !in day.blocks.indices || to !in day.blocks.indices) return@mapDay day
            if (!canEdit(day.blocks[from])) return@mapDay day
            if (crossesFixedBlock(day.blocks, from, to)) return@mapDay day
            val blocks = day.blocks.toMutableList()
            blocks.add(to, blocks.removeAt(from))
            day.copy(blocks = blocks)
        }

    /**
     * [from] 에서 [to] 로 가는 길에 옮길 수 없는 블록이 있는가. (API 명세 §5-10)
     *
     * 대회 블록의 위치는 시스템이 고정하므로 그 사이를 지나는 이동은 저장할 수 없다.
     */
    private fun crossesFixedBlock(blocks: List<ItineraryBlock>, from: Int, to: Int): Boolean {
        val range = if (from < to) (from + 1)..to else to until from
        return range.any { !canEdit(blocks[it]) }
    }

    /**
     * 하루치 지도 핀. 좌표가 있는 블록만 **순서대로 번호를 붙인다.** (SPEC §5.7 · §3-8)
     *
     * 대회 블록도 포함한다 — 편집은 막지만 지도에는 나와야 한다.
     */
    fun dayPins(day: ItineraryDay?): List<MapPin> {
        val pins = mutableListOf<MapPin>()
        day?.blocks?.forEach { block ->
            val place = block.place ?: return@forEach
            if (!place.lat.isFinite() || !place.lng.isFinite()) return@forEach
            pins += MapPin(
                n = pins.size + 1,
                blockId = block.id,
                lat = place.lat,
                lng = place.lng,
                title = place.name.ifEmpty { block.title },
                catKey = block.catKey,
            )
        }
        return pins
    }

    /** 동선 전체 장소 수. 저장·요약에 쓴다. 대회 블록도 센다. (SPEC §5.7) */
    fun countPlaces(days: List<ItineraryDay>): Int =
        days.sumOf { day -> day.blocks.count { it.place != null } }

    // ── 내부 ────────────────────────────────────────────────────

    private inline fun mapDay(
        days: List<ItineraryDay>,
        dayIndex: Int,
        transform: (ItineraryDay) -> ItineraryDay,
    ): List<ItineraryDay> {
        if (dayIndex !in days.indices) return days
        return days.mapIndexed { i, day -> if (i == dayIndex) transform(day) else day }
    }

    /**
     * 동선 전체에서 겹치지 않는 새 블록 id.
     *
     * 엔진이 `blk_1`부터 붙이므로 가장 큰 번호 다음을 쓴다. 특정 일자가 아니라
     * **전체**를 보는 이유는, 일자별로만 세면 다른 날 블록과 id 가 겹치기 때문이다.
     */
    private fun nextBlockId(days: List<ItineraryDay>): String {
        val max = days.asSequence()
            .flatMap { it.blocks.asSequence() }
            .mapNotNull { it.id.removePrefix(BLOCK_ID_PREFIX).toIntOrNull() }
            .maxOrNull() ?: 0
        return "$BLOCK_ID_PREFIX${max + 1}"
    }

    private const val BLOCK_ID_PREFIX = "blk_"
}

/** 지도 핀 하나. 번호는 그날 순서다. (SPEC §3-8) */
data class MapPin(
    val n: Int,
    val blockId: String,
    val lat: Double,
    val lng: Double,
    val title: String,
    val catKey: BlockCategory,
)
