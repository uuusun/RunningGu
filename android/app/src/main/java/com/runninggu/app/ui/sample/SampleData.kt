package com.runninggu.app.ui.sample

import com.runninggu.app.domain.today
import com.runninggu.app.ui.model.FestivalSummary
import com.runninggu.app.ui.model.NearbyFestival
import com.runninggu.app.ui.model.RaceSummary
import java.time.LocalDate

/**
 * 화면 확인용 임시 데이터. 값은 목업 v2(docs/mockup-design)의 예시를 따랐다.
 *
 * TODO(AP-14): 백엔드 API 클라이언트가 붙으면 이 파일을 통째로 삭제한다.
 * 화면은 UiState만 보므로 각 ViewModel의 호출부만 바꾸면 된다.
 */
object SampleData {

    private val today: LocalDate = today()

    private fun race(
        id: String,
        name: String,
        region: String,
        venue: String,
        inDays: Long,
        startTime: String,
        regEndInDays: Long?,
        eventTypes: List<String>,
        source: String = "마라톤온라인",
        regStartInDays: Long = -30,
        organizer: String = "$region 육상연맹",
        active: Boolean = true,
        serverId: Long? = null,
    ) = RaceSummary(
        id = id,
        serverId = serverId,
        active = active,
        name = name,
        region = region,
        venue = venue,
        date = today.plusDays(inDays),
        startTime = startTime,
        regStart = today.plusDays(regStartInDays),
        regEnd = regEndInDays?.let { today.plusDays(it) },
        eventTypes = eventTypes,
        source = source,
        checked = today.minusDays(1),
        organizer = organizer,
        officialUrl = "https://example.com/$id",
    )

    /**
     * 공개 목록(S1·S2). (API 명세 §3-1 🔒)
     *
     * `active=true AND contest_date >= 오늘(KST)` 고정이라 **여기에는 비활성·지난 대회가
     * 들어오지 않는다.** 스텁도 그 규칙을 지켜야 화면이 실제와 같은 것을 본다.
     *
     * `serverId` 는 비워 둔다 — 가짜 canonical id 를 만들지 않는다(#66 리뷰). 데모용
     * 대회 id 는 `ResultViewModel` 이 스텁 저장소일 때만 쓰고 서버로 나가지 않는다.
     */
    val races: List<RaceSummary> = publicRaces()

    /**
     * 찜·저장 동선에서만 만나는 대회. (API 명세 §7-C · 결정-46)
     *
     * 공개 목록에서는 빠졌지만 참조를 지키려고 남겨 둔 것들이다. 둘을 일부러 다르게 뒀다 —
     * **지난 대회**(흐림)와 **원천에서 사라진 대회**(흐림 + "정보 제공 종료"). 화면에서
     * 두 경로를 다 확인할 수 있다.
     */
    private val archivedRaces: List<RaceSummary> = listOf(
        race("chungbuk-past", "충북 청남대 벚꽃마라톤", "충북", "청남대", -12, "09:00", -40, listOf("하프", "10K")),
        race("jeonbuk-ended", "전북 새만금 바람길런", "전북", "새만금 방조제", 34, "08:30", 20, listOf("풀", "하프"), active = false),
    )

    /** 공개 목록 + 보관된 것. 상세(S3)와 찜 목록(S10)이 본다. */
    val allRaces: List<RaceSummary> = races + archivedRaces

    private fun publicRaces(): List<RaceSummary> = listOf(
        race("seoul-hangang", "서울 한강 러닝 페스티벌", "서울", "여의도한강공원", 18, "09:00", 4, listOf("10K", "5K")),
        race("sejong-lake", "세종 호수공원 마라톤", "세종", "세종 호수공원", 21, "08:00", 9, listOf("하프", "10K", "5K")),
        race("incheon-bridge", "인천 송도 브릿지런", "인천", "송도 센트럴파크", 26, "08:30", -3, listOf("하프", "10K")),
        race("busan-sea", "부산 바다마라톤", "부산", "광안리해수욕장", 40, "08:30", 27, listOf("풀", "하프", "10K")),
        race("daegu-color", "대구 컬러런", "대구", "두류공원", 45, "10:00", 30, listOf("5K")),
        race("gangwon-trail", "강원 대관령 트레일런", "강원", "대관령 양떼목장", 52, "07:00", 38, listOf("하프", "10K"), source = "로드런"),
        race("gwangju-flower", "광주 꽃길 마라톤", "광주", "5·18 기념공원", 58, "09:00", 44, listOf("10K", "5K")),
        race("jeju-olle", "제주 올레 트레일런", "제주", "성산일출봉 일원", 75, "07:30", 62, listOf("하프", "10K"), source = "로드런"),
        race("gyeonggi-lake", "경기 호수공원 하프", "경기", "일산 호수공원", 82, "08:00", 68, listOf("하프", "10K", "5K")),
        race("daejeon-science", "대전 사이언스런", "대전", "엑스포시민광장", 90, "09:30", 76, listOf("10K", "5K")),
        race("ulsan-whale", "울산 고래축제 마라톤", "울산", "태화강국가정원", 96, "08:00", 82, listOf("풀", "하프", "10K")),
        race("jeonnam-green", "전남 순천만 갈대런", "전남", "순천만습지", 104, "08:30", null, listOf("하프", "10K"), regStartInDays = 20),
    )

    /**
     * id로 대회 한 건 찾기. S3 상세가 쓴다. 없으면 null → 404 CONTEST_NOT_FOUND에 대응.
     *
     * **공개 목록이 아니라 [allRaces] 를 본다.** 비활성 대회도 상세 조회는 유지하고
     * `active=false` 로 돌려주는 게 계약이다 — 404 로 숨기지 않는다(§3-4 · 결정-46).
     */
    fun raceById(id: String): RaceSummary? = allRaces.firstOrNull { it.id == id }

    /**
     * 대회별 인근 축제. (API 명세 §3-5 응답 형태)
     *
     * 목록에 없는 대회는 빈 리스트 — S3의 빈 상태("대회 기간에 열리는 인근 축제가 없어요")를
     * 확인할 수 있게 일부러 일부 대회만 채워뒀다.
     */
    fun nearbyFestivals(raceId: String): List<NearbyFestival> = nearbyFestivals[raceId].orEmpty()

    private fun festival(
        contentId: String,
        name: String,
        startInDays: Long,
        endInDays: Long,
        distanceKm: Double,
        address: String,
    ) = NearbyFestival(
        contentId = contentId,
        name = name,
        startDate = today.plusDays(startInDays),
        endDate = today.plusDays(endInDays),
        distanceKm = distanceKm,
        imageUrl = null, // TODO(AP-19): 서버 firstimage 연결 + Coil 로딩
        address = address,
    )

    private val nearbyFestivals: Map<String, List<NearbyFestival>> = mapOf(
        "seoul-hangang" to listOf(
            festival("2764321", "여의도 한강 빛섬축제", 14, 24, 0.8, "서울특별시 영등포구"),
            festival("2764322", "서울 세계불꽃축제", 20, 20, 3.2, "서울특별시 영등포구"),
            festival("2764323", "노들섬 재즈 페스티벌", 24, 26, 5.1, "서울특별시 용산구"),
        ),
        "sejong-lake" to listOf(
            festival("2765001", "세종 호수공원 물빛축제", 18, 22, 0.4, "세종특별자치시 연기면"),
            festival("2765002", "조치원 복숭아축제", 25, 28, 11.6, "세종특별자치시 조치원읍"),
        ),
        "busan-sea" to listOf(
            festival("2766010", "부산 바다축제", 36, 44, 1.1, "부산광역시 수영구"),
            festival("2766011", "광안리 어방축제", 38, 40, 0.6, "부산광역시 수영구"),
            festival("2766012", "부산국제영화제", 41, 50, 4.9, "부산광역시 해운대구"),
            festival("2766013", "해운대 모래축제", 44, 47, 6.3, "부산광역시 해운대구"),
        ),
        "jeju-olle" to listOf(
            festival("2767100", "성산일출축제", 70, 76, 1.4, "제주특별자치도 서귀포시"),
        ),
    )

    val festivals: List<FestivalSummary> = listOf(
        FestivalSummary("fest-busan", "부산 바다축제", "부산", "08.01~08.09", isOngoing = true),
        FestivalSummary("fest-bonghwa", "봉화 은어축제", "경북", "08.01~08.10", isOngoing = true),
        FestivalSummary("fest-sejong", "세종 호수공원 물빛축제", "세종", "08.20~08.24", isOngoing = false),
        FestivalSummary("fest-pyeongchang", "평창 백일홍축제", "강원", "08.28~09.13", isOngoing = false),
    )
}
