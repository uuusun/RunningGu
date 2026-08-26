package com.runninggu.app.data.repository

import com.runninggu.app.data.model.HotelSnapshot
import com.runninggu.app.data.model.ItineraryResult
import com.runninggu.app.data.model.SavedItinerary
import com.runninggu.app.data.model.SavedItineraryDetail
import com.runninggu.app.data.remote.mapper.toDetail
import com.runninggu.app.data.remote.mapper.toSaveRequest
import com.runninggu.app.data.remote.mapper.toSavedItinerary
import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.ItineraryApi
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.dto.GenerateItineraryRequestDto
import com.runninggu.app.data.remote.dto.GenerateItineraryResponse
import com.runninggu.app.data.remote.dto.HotelDto
import com.runninggu.app.data.remote.mapper.toResult
import com.runninggu.app.data.remote.mapper.toServerName
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.PoiCategory
import com.runninggu.app.domain.Recovery
import kotlinx.coroutines.delay
import java.time.LocalDate
import com.runninggu.app.data.model.PoiItem

/** `POST /api/itineraries/generate` 요청. (API 명세 §5-1) */
data class GenerateItineraryRequest(
    /** canonical `CONTEST.id`. 번들만 있는 대회는 생성을 부를 수 없다 ([Contest.serverId]). */
    val contestId: Long,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val event: EventType,
    val themes: List<PoiCategory>,
    /** 숙소. null 이면 서버가 대회장 중심으로 슬롯을 채운다. (API 명세 §5-1 · SPEC §4.9) */
    val hotel: HotelInput? = null,
)

/**
 * 요청에 실을 숙소.
 *
 * 화면의 후보 모델(`PoiItem`)을 그대로 쓰지 않는다 — `data` 가 `ui` 를 알면 안 된다
 * (AGENTS 2장). 화면에서 필요한 세 값만 옮겨 담는다.
 */
data class HotelInput(val name: String, val lat: Double, val lng: Double)

/**
 * 동선 생성 창구. (SPEC 결정-41)
 *
 * **앱은 동선을 만들지 않는다.** 서버가 KTO·카카오 POI 조회·캐시·폴백과 §5.6 조립을 한 요청에서
 * 수행하고, 앱은 응답을 표시하고 저장 전 USER 블록만 편집한다.
 */
interface ItineraryRepository {
    suspend fun generate(request: GenerateItineraryRequest): ItineraryResult

    /**
     * 내 동선 목록 한 장. (`GET /api/itineraries` · §5-4)
     *
     * **비활성 대회의 동선도 그대로 온다**(§5-4). 걸러 내면 사용자가 저장한 것이 말없이
     * 사라진다 — 흐리게 보여 주고 왜 그런지 알리는 것이 맞다.
     */
    suspend fun list(page: Int = 0, size: Int = DEFAULT_PAGE_SIZE): SavedItineraryPage =
        SavedItineraryPage()

    /**
     * 편집을 마친 동선을 저장한다. (`POST /api/itineraries` · §5-2 🔒)
     *
     * **같은 대회·같은 기간이면 새로 쌓이지 않고 교체된다.** 그래서 결과가 [SaveOutcome]
     * 이다 — 화면이 "저장했어요" 와 "이전 것을 덮어썼어요" 를 가를 수 있어야 한다.
     *
     * 기본 구현은 [list] 와 달리 **예외를 던진다.** 생성만 쓰는 구현이 여럿이라 기본
     * 구현 자체는 두되, 조용히 성공한 척하면 저장 안 된 동선을 저장됐다고 그리게 된다.
     */
    suspend fun save(result: ItineraryResult): SaveOutcome =
        throw UnsupportedOperationException("이 구현은 동선을 저장하지 않는다 (§5-2)")

    /**
     * 저장 동선 상세. (`GET /api/itineraries/{id}` · §5-5)
     *
     * 마이[동선] 카드 → S7 복원에 쓴다. [list] 와 같은 이유로 기본 구현을 둔다 — 생성만
     * 쓰는 구현이 여럿이라, 없으면 빈 override 만 늘어난다.
     */
    suspend fun detail(id: Long): SavedItineraryDetail =
        throw UnsupportedOperationException("이 구현은 저장 동선을 복원하지 않는다")

    /**
     * 저장 동선 삭제. (`DELETE /api/itineraries/{id}` · §5-6)
     *
     * [list] 와 함께 **기본 구현을 둔다.** 생성만 쓰는 구현(S7 스텁·위저드 테스트)이
     * 여럿인데 목록까지 채우게 하면 빈 override 만 늘어난다. 서버 구현이 둘 다 덮는다.
     */
    suspend fun delete(id: Long) = Unit

    companion object {
        /** 개인 목록 기본 페이지 크기 🔒(§0-4). */
        const val DEFAULT_PAGE_SIZE = 20
    }
}

/**
 * 저장 결과. (§5-2)
 *
 * [replaced] 는 같은 `(대회, 시작일, 종료일)` 동선이 이미 있어 **교체**됐다는 뜻이다.
 * 사용자에게는 다른 일이라 문구를 가른다 — 새로 담은 것과 덮어쓴 것은 다르다.
 */
data class SaveOutcome(val id: Long, val replaced: Boolean)

/** 저장 동선 목록 한 장. (§5-4) */
data class SavedItineraryPage(
    val itineraries: List<SavedItinerary> = emptyList(),
    val hasNext: Boolean = false,
    val totalElements: Long = 0,
)

/** 서버 구현. (API 명세 §5-1 — 게스트도 부를 수 있다) */
class RemoteItineraryRepository(private val api: ItineraryApi) : ItineraryRepository {
    override suspend fun generate(request: GenerateItineraryRequest): ItineraryResult =
        apiCall { api.generate(request.toDto()).toResult() }

    override suspend fun list(page: Int, size: Int): SavedItineraryPage = apiCall {
        val dto = api.list(page = page, size = size)
        SavedItineraryPage(
            itineraries = dto.content.map { it.toSavedItinerary() },
            hasNext = dto.page.hasNext,
            totalElements = dto.page.totalElements,
        )
    }

    override suspend fun save(result: ItineraryResult): SaveOutcome = apiCall {
        val dto = api.save(result.toSaveRequest())
        SaveOutcome(id = dto.id, replaced = dto.replaced)
    }

    override suspend fun detail(id: Long): SavedItineraryDetail =
        apiCall { api.detail(id).toDetail() }

    override suspend fun delete(id: Long) = apiCall { api.delete(id) }
}

/**
 * 요청 모델 → 전송 DTO. (§5-1)
 *
 * 날짜는 KST 비즈니스 날짜라 `toString()` 이 곧 `YYYY-MM-DD` 다. enum 은 서버와 같은
 * 대문자 이름을 그대로 보낸다 — 라벨(한국어)을 보내면 안 된다.
 */
internal fun GenerateItineraryRequest.toDto() = GenerateItineraryRequestDto(
    contestId = contestId,
    startDate = startDate.toString(),
    endDate = endDate.toString(),
    // 부록 C 는 K5·K10 이다. enum 이름(FIVE_K·TEN_K)을 그대로 보내면 서버가 못 읽는다
    event = event.toServerName(),
    themes = themes.map { it.name },
    hotel = hotel?.let { HotelDto(it.name, it.lat, it.lng) },
)

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

    override suspend fun generate(request: GenerateItineraryRequest): ItineraryResult {
        delay(NETWORK_DELAY_MS) // 서버 왕복을 흉내 낸다 — 로딩 상태를 화면에서 확인할 수 있게.

        val fixture = if (Recovery[request.event].noHard) RECOVERY_FIXTURE else NORMAL_FIXTURE
        val response = ApiJson.decodeFromString(GenerateItineraryResponse.serializer(), fixture)
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
        // 스냅샷은 **요청을 그대로 되비춘다.** fixture 의 리터럴을 두면 §5-2 저장 요청이
        // 엉뚱한 대회·기간으로 나간다 — 화면에는 안 보여서 저장할 때야 드러난다.
        return copy(
            days = aligned,
            request = this.request.copy(
                contestId = request.contestId,
                event = request.event.toServerName(),
                themes = request.themes.map { it.name },
                startDate = request.startDate.toString(),
                endDate = request.endDate.toString(),
                hotel = request.hotel?.let { HotelSnapshot(it.name, it.lat, it.lng) },
            ),
        )
    }

    private const val NETWORK_DELAY_MS = 600L

    /** 하프·풀 — 회복 배지와 온천 블록이 있다. (API 명세 §5-1 예시) */
    private val RECOVERY_FIXTURE = """
        {
          "title": "2박 3일",
          "event": "HALF",
          "contestId": 1,
          "themes": ["TOUR", "WELLNESS"],
          "startDate": "2026-09-03",
          "endDate": "2026-09-05",
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
          "event": "K10",
          "contestId": 1,
          "themes": ["TOUR", "FOOD"],
          "startDate": "2026-09-03",
          "endDate": "2026-09-05",
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
