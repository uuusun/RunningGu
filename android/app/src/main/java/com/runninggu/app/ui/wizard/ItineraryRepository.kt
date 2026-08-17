package com.runninggu.app.ui.wizard

import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.PoiCategory
import com.runninggu.app.domain.Recovery
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** `POST /api/itineraries/generate` 요청. (API 명세 §5-1) */
data class GenerateItineraryRequest(
    val contestId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val event: EventType,
    val themes: List<PoiCategory>,
)

/**
 * 동선 생성 창구. (SPEC 결정-41)
 *
 * **앱은 동선을 만들지 않는다.** 서버가 KTO·카카오 POI 조회·캐시·폴백과 §5.6 조립을 한 요청에서
 * 수행하고, 앱은 응답을 표시하고 저장 전 USER 블록만 편집한다.
 *
 * TODO(AP-14): `data/remote` 의 Retrofit 구현으로 교체한다.
 */
interface ItineraryRepository {
    suspend fun generate(request: GenerateItineraryRequest): ItineraryResult
}

/**
 * 백엔드가 준비되기 전까지 쓰는 스텁. (매핑표 §12)
 *
 * > Android 는 springdoc 과 동일한 JSON fixture 로 FakeRepository 를 만들고,
 * > 백엔드 준비 후 Retrofit 구현으로 교체한다.
 *
 * **여기서 동선을 조립하지 않는다.** 미리 준비한 응답 fixture 를 골라 돌려줄 뿐이다 —
 * 회복 종목(하프·풀)과 아닌 종목(5K·10K)의 화면이 달라서 둘을 준비했다.
 * 날짜·대회명 같은 표시값만 요청에 맞춰 바꾼다.
 */
object FakeItineraryRepository : ItineraryRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(request: GenerateItineraryRequest): ItineraryResult {
        delay(NETWORK_DELAY_MS) // 서버 왕복을 흉내 낸다 — 로딩 상태를 화면에서 확인할 수 있게.

        val fixture = if (Recovery[request.event].noHard) RECOVERY_FIXTURE else NORMAL_FIXTURE
        val response = json.decodeFromString<GenerateItineraryResponse>(fixture)
        return response.toResult().alignTo(request)
    }

    /**
     * fixture 의 날짜를 요청 기간에 맞춘다.
     *
     * fixture 는 3일치라 기간이 더 짧으면 잘라 쓰고, 길면 fixture 길이까지만 준다 —
     * 스텁이라 그 이상은 만들지 않는다. 실제 서버는 최대 7일을 채워 준다(API 명세 §5-1).
     */
    private fun ItineraryResult.alignTo(request: GenerateItineraryRequest): ItineraryResult {
        val dayCount = (request.startDate.datesUntil(request.endDate.plusDays(1)).count()).toInt()
        val aligned = days.take(dayCount).mapIndexed { index, day ->
            val date = request.startDate.plusDays(index.toLong())
            day.copy(
                date = date,
                dateLabel = "%02d.%02d".format(date.monthValue, date.dayOfMonth),
            )
        }
        return copy(days = aligned)
    }

    private const val NETWORK_DELAY_MS = 600L

    /** 하프·풀 — 회복 배지와 온천 블록이 있다. (API 명세 §5-1 예시) */
    private val RECOVERY_FIXTURE = """
        {
          "title": "2박 3일",
          "event": "HALF",
          "recovery": { "label": "D+1 회복 모드", "note": "온천+짧은 산책(고강도 제외)" },
          "days": [
            {
              "dayIndex": -1, "date": "2026-09-03", "dayLabel": "D-1",
              "recovery": false, "note": "내일 완주 · 가볍게 먹고 푹 쉬기",
              "blocks": [
                { "startTime": "15:00", "title": "숙소 체크인", "category": "LODGING",
                  "placeName": "시티 호텔", "address": "여의도동 1", "lat": 37.52, "lng": 126.93,
                  "description": "여장 풀기" },
                { "startTime": "18:30", "title": "카보로딩 저녁", "category": "FOOD",
                  "placeName": "한밭식당", "address": "여의도동 12", "lat": 37.52, "lng": 126.93,
                  "description": "탄수화물 보충 · 무리 없는 메뉴" }
              ]
            },
            {
              "dayIndex": 0, "date": "2026-09-04", "dayLabel": "D-day",
              "recovery": false, "note": "완주 후 온천·휴식 권장",
              "blocks": [
                { "startTime": "09:00", "title": "🏁 스타트", "category": "RACE",
                  "placeName": "여의도한강공원", "address": "여의도동", "lat": 37.52, "lng": 126.93,
                  "description": "완주 · 결승 후 샤워",
                  "blockType": "RACE", "systemManaged": true },
                { "startTime": "11:00", "title": "온천·회복", "category": "WELLNESS",
                  "placeName": "시티 온천", "address": "영등포구 3", "lat": 37.51, "lng": 126.90,
                  "description": "완주 근육 회복" },
                { "startTime": "14:30", "title": "가벼운 관광", "category": "TOUR",
                  "placeName": "중앙공원 전망대", "address": "영등포구 7", "lat": 37.53, "lng": 126.92,
                  "description": "평지 위주 가벼운 코스" },
                { "startTime": "18:00", "title": "회복 저녁", "category": "FOOD",
                  "placeName": "소문난 국밥", "address": "영등포구 9", "lat": 37.51, "lng": 126.91,
                  "description": "소화 잘 되는 회복식" }
              ]
            },
            {
              "dayIndex": 1, "date": "2026-09-05", "dayLabel": "D+1",
              "recovery": true, "note": "온천+짧은 산책(고강도 제외)",
              "blocks": [
                { "startTime": "10:00", "title": "온천·족욕", "category": "WELLNESS",
                  "placeName": "스파랜드", "address": "마포구 2", "lat": 37.54, "lng": 126.94,
                  "description": "고강도 제외 · 회복 위주" },
                { "startTime": "12:30", "title": "로컬 점심", "category": "FOOD",
                  "placeName": "골목 손칼국수", "address": "마포구 5", "lat": 37.54, "lng": 126.95,
                  "description": "그 지역 별미" },
                { "startTime": "14:30", "title": "오후 관광", "category": "TOUR",
                  "placeName": "역사문화거리", "address": "종로구 1", "lat": 37.57, "lng": 126.98,
                  "description": "관광지" },
                { "startTime": "17:00", "title": "체크아웃·귀가", "category": "LODGING",
                  "placeName": "시티 호텔", "address": "여의도동 1", "lat": 37.52, "lng": 126.93,
                  "description": "여행 마무리" }
              ]
            }
          ]
        }
    """.trimIndent()

    /** 5K·10K — 회복 배지가 없고 오후가 자유 관광이다. */
    private val NORMAL_FIXTURE = """
        {
          "title": "2박 3일",
          "event": "TEN_K",
          "recovery": null,
          "days": [
            {
              "dayIndex": -1, "date": "2026-09-03", "dayLabel": "D-1",
              "recovery": false, "note": "내일 완주 · 가볍게 먹고 푹 쉬기",
              "blocks": [
                { "startTime": "15:00", "title": "숙소 체크인", "category": "LODGING",
                  "placeName": "시티 호텔", "address": "여의도동 1", "lat": 37.52, "lng": 126.93,
                  "description": "여장 풀기" },
                { "startTime": "18:30", "title": "카보로딩 저녁", "category": "FOOD",
                  "placeName": "한밭식당", "address": "여의도동 12", "lat": 37.52, "lng": 126.93,
                  "description": "탄수화물 보충 · 무리 없는 메뉴" }
              ]
            },
            {
              "dayIndex": 0, "date": "2026-09-04", "dayLabel": "D-day",
              "recovery": false, "note": "완주 후 가벼운 관광·축제",
              "blocks": [
                { "startTime": "09:00", "title": "🏁 스타트", "category": "RACE",
                  "placeName": "여의도한강공원", "address": "여의도동", "lat": 37.52, "lng": 126.93,
                  "description": "완주 · 결승 후 샤워",
                  "blockType": "RACE", "systemManaged": true },
                { "startTime": "13:00", "title": "오후 자유 관광", "category": "TOUR",
                  "placeName": "중앙공원 전망대", "address": "영등포구 7", "lat": 37.53, "lng": 126.92,
                  "description": "관광지" },
                { "startTime": "15:30", "title": "카페 한 잔", "category": "CAFE",
                  "placeName": "로스터리 1호점", "address": "영등포구 11", "lat": 37.52, "lng": 126.92,
                  "description": "완주 후 휴식" },
                { "startTime": "18:30", "title": "맛집 저녁", "category": "FOOD",
                  "placeName": "소문난 국밥", "address": "영등포구 9", "lat": 37.51, "lng": 126.91,
                  "description": "오늘은 잘 먹는 날" }
              ]
            },
            {
              "dayIndex": 1, "date": "2026-09-05", "dayLabel": "D+1",
              "recovery": false, "note": "일반 관광",
              "blocks": [
                { "startTime": "10:00", "title": "오전 관광", "category": "TOUR",
                  "placeName": "호수 산책로", "address": "송파구 1", "lat": 37.51, "lng": 127.10,
                  "description": "관광지" },
                { "startTime": "12:30", "title": "로컬 점심", "category": "FOOD",
                  "placeName": "골목 손칼국수", "address": "마포구 5", "lat": 37.54, "lng": 126.95,
                  "description": "그 지역 별미" },
                { "startTime": "14:30", "title": "오후 관광", "category": "TOUR",
                  "placeName": "역사문화거리", "address": "종로구 1", "lat": 37.57, "lng": 126.98,
                  "description": "관광지" },
                { "startTime": "17:00", "title": "체크아웃·귀가", "category": "LODGING",
                  "placeName": "시티 호텔", "address": "여의도동 1", "lat": 37.52, "lng": 126.93,
                  "description": "여행 마무리" }
              ]
            }
          ]
        }
    """.trimIndent()
}
