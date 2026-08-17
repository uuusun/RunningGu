package com.runninggu.app.domain

/**
 * 동선 생성 엔진. (SPEC §5.6 v2 확정판 · 원본 `reference-web/src/lib/runninggu/engine.js`)
 *
 * 대회·숙소·종목·취향·기간을 받아 **편집 가능한 초안** [Itinerary] 를 만든다.
 * UI·지도에 의존하지 않는 순수 모듈이라 기기 없이 검증된다.
 *
 * **원본에서 바꾼 것** — `docs/domain-logic-audit.md` A1·A2·A4
 * - 산책 블록 3개(D-1 20:00 · D-day 20:30 · D+N 08:00)를 **넣지 않는다** (§5.6 삭제 확정)
 * - 산책용 `walk` 풀(숙소 기준 nature 6건) 적재도 함께 뺐다. `sources` 에서 `walk` 키가 사라진다
 * - 대회 블록에 [BlockType.RACE] · `systemManaged` 를 붙인다 (§5.6-7)
 */
class ItineraryEngine(private val poiSource: PoiSource) {

    suspend fun build(plan: ItineraryPlan): Itinerary {
        val rule = Recovery[plan.event]
        val themes = plan.themes.ifEmpty { PoiCategory.DEFAULT_THEMES }

        // ── 카테고리 풀 결정 (§5.6-2) ──
        // {맛집, 관광지} ∪ 취향. 회복이 필요하면 웰니스, 아니면 카페를 보탠다.
        val poolKeys = buildSet {
            add(PoiCategory.FOOD)
            add(PoiCategory.TOUR)
            addAll(themes)
            add(if (rule.noHard) PoiCategory.WELLNESS else PoiCategory.CAFE)
        }

        // ── POI 적재 (§5.6-3) — 대회장 중심 8건 ──
        val venue = LatLng(plan.race.lat, plan.race.lng)
        val pools = mutableMapOf<PoiCategory, List<Poi>>()
        val sources = mutableMapOf<PoiCategory, PoiSourceKind>()
        for (key in poolKeys) {
            val result = poiSource.load(key, venue, POI_COUNT)
            pools[key] = result.places
            sources[key] = result.source
        }

        val picker = Picker(pools, themes)
        val stayPlace = plan.stay?.copy(desc = "숙소", url = "")
        val venuePlace = plan.race.toVenuePoi()

        // ── 일자별 블록 (§5.6-4) ──
        var seq = 0
        val days = dateRange(plan.start, plan.end).map { date ->
            val off = diffDays(plan.race.date, date)
            val blocks = mutableListOf<ItineraryBlock>()

            fun add(
                time: String,
                title: String,
                catKey: BlockCategory,
                place: Poi?,
                desc: String = "",
                blockType: BlockType = BlockType.USER,
            ) {
                blocks += ItineraryBlock(
                    id = "blk_${++seq}",
                    time = time,
                    title = title,
                    catKey = catKey,
                    place = place,
                    desc = desc.ifEmpty { place?.desc.orEmpty() },
                    blockType = blockType,
                    systemManaged = blockType == BlockType.RACE,
                )
            }

            val note = when {
                off < 0 -> {
                    add("15:00", "숙소 체크인", BlockCategory.LODGING, stayPlace, stayPlace?.addr.orEmpty().ifEmpty { "여장 풀기" })
                    add("18:30", "카보로딩 저녁", BlockCategory.FOOD, picker.pick(PoiCategory.FOOD), "탄수화물 보충 · 무리 없는 메뉴")
                    "내일 완주 · 가볍게 먹고 푹 쉬기"
                }

                off == 0 -> {
                    add(
                        time = plan.race.startTime.ifEmpty { DEFAULT_START_TIME },
                        title = "🏁 ${plan.race.name} 스타트",
                        catKey = BlockCategory.RACE,
                        place = venuePlace,
                        desc = "${plan.event.label} 완주 · 결승 후 샤워",
                        blockType = BlockType.RACE,
                    )
                    if (rule.noHard) {
                        add("11:00", "온천·회복", BlockCategory.WELLNESS, picker.pick(PoiCategory.WELLNESS), "완주 근육 회복")
                        // 가벼운 관광은 하프만. 풀은 도보를 최소로 한다. (§5.6-4)
                        if (plan.event == EventType.HALF) {
                            val tour = picker.pick(PoiCategory.TOUR)
                            add("14:30", "가벼운 관광", BlockCategory.TOUR, tour, tour?.desc.orEmpty().ifEmpty { "평지 위주 가벼운 코스" })
                        }
                        add("18:00", "회복 저녁", BlockCategory.FOOD, picker.pick(PoiCategory.FOOD), "소화 잘 되는 회복식")
                    } else {
                        val theme = picker.pickTheme()
                        add("13:00", "오후 자유 관광", BlockCategory.of(theme.category), theme.place)
                        add("15:30", "카페 한 잔", BlockCategory.CAFE, picker.pick(PoiCategory.CAFE), "완주 후 휴식")
                        add("18:30", "맛집 저녁", BlockCategory.FOOD, picker.pick(PoiCategory.FOOD), "오늘은 잘 먹는 날")
                    }
                    rule.dday
                }

                else -> {
                    if (rule.noHard) {
                        add("10:00", "온천·족욕", BlockCategory.WELLNESS, picker.pick(PoiCategory.WELLNESS), "고강도 제외 · 회복 위주")
                    } else {
                        add("10:00", "오전 관광", BlockCategory.TOUR, picker.pick(PoiCategory.TOUR))
                    }
                    add("12:30", "로컬 점심", BlockCategory.FOOD, picker.pick(PoiCategory.FOOD), "그 지역 별미")
                    val theme = picker.pickTheme()
                    add("14:30", "오후 관광", BlockCategory.of(theme.category), theme.place)
                    if (date == plan.end) {
                        add("17:00", "체크아웃·귀가", BlockCategory.LODGING, stayPlace, "여행 마무리")
                    }
                    rule.dplus
                }
            }

            ItineraryDay(
                date = date,
                off = off,
                label = offLabel(off),
                dateLabel = shortKo(date),
                note = note,
                blocks = blocks,
            )
        }

        return Itinerary(
            days = days,
            sources = sources,
            recovery = recoveryBadgeOf(plan.event, days.map { it.off }),
            plan = plan,
        )
    }

    private companion object {
        /** 카테고리별 적재 개수. (SPEC §5.6-3 · §8.1) */
        const val POI_COUNT = 8

        /** 대회 출발시각이 비었을 때. */
        const val DEFAULT_START_TIME = "08:00"
    }
}

/** 대회 출발 블록에 쓰는 의사 POI. */
private fun RaceInfo.toVenuePoi() = Poi(
    name = venue.ifEmpty { name },
    lat = lat,
    lng = lng,
    desc = "대회장",
    addr = region,
    url = officialUrl,
)

/**
 * 장소를 고른다. **전체 일정에서 중복 없이** 쓰기 위해 이미 쓴 이름을 기억한다. (SPEC §5.6-4)
 *
 * 풀이 바닥나면 원본과 같이 첫 항목을 다시 준다 — 3일 내내 다른 곳을 보장하지는 않는다.
 * 카테고리당 8건이므로 기간이 길면 반복이 생긴다.
 */
private class Picker(
    private val pools: Map<PoiCategory, List<Poi>>,
    private val themes: List<PoiCategory>,
) {
    private val used = mutableSetOf<String>()

    fun pick(category: PoiCategory): Poi? {
        val places = pools[category].orEmpty()
        places.firstOrNull { it.name !in used }?.let {
            used += it.name
            return it
        }
        return places.firstOrNull()
    }

    /**
     * 테마 우선 선택. `[취향…, 관광지, 자연, 카페, 역사]` 순으로 **미사용 POI 가 남아 있는**
     * 첫 카테고리를 쓴다. 전부 소진되면 관광지로 떨어진다. (SPEC §5.6-5)
     */
    fun pickTheme(): Picked {
        val order = themes + listOf(
            PoiCategory.TOUR,
            PoiCategory.NATURE,
            PoiCategory.CAFE,
            PoiCategory.HISTORY,
        )
        for (category in order) {
            val places = pools[category] ?: continue
            if (places.any { it.name !in used }) return Picked(category, pick(category))
        }
        return Picked(PoiCategory.TOUR, pick(PoiCategory.TOUR))
    }

    data class Picked(val category: PoiCategory, val place: Poi?)
}
