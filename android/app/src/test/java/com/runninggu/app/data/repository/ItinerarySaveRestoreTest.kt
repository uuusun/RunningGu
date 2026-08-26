package com.runninggu.app.data.repository

import com.runninggu.app.data.model.HotelSnapshot
import com.runninggu.app.data.model.ItineraryRequestSnapshot
import com.runninggu.app.data.model.ItineraryResult
import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.ItineraryApi
import com.runninggu.app.data.remote.dto.GenerateItineraryRequestDto
import com.runninggu.app.data.remote.dto.GenerateItineraryResponse
import com.runninggu.app.data.remote.dto.ItineraryDetailDto
import com.runninggu.app.data.remote.dto.ItinerarySummaryDto
import com.runninggu.app.data.remote.dto.PageDto
import com.runninggu.app.data.remote.dto.SaveItineraryRequestDto
import com.runninggu.app.data.remote.dto.SaveItineraryResponseDto
import com.runninggu.app.data.remote.mapper.toResult
import com.runninggu.app.data.remote.mapper.toSaveRequest
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.BlockType
import com.runninggu.app.domain.ItineraryBlock
import com.runninggu.app.domain.Poi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 동선 저장·복원 계약. (API 명세 §5-2 · §5-5)
 *
 * 이 둘이 없으면 **만들어도 담을 수 없고, 담아도 다시 열 수 없다.** 핵심 여정이 여기서 끊긴다.
 */
class ItinerarySaveRestoreTest {

    private class FakeApi(
        private val saveResponse: SaveItineraryResponseDto = SaveItineraryResponseDto(42),
        private val detailJson: String = DETAIL_JSON,
    ) : ItineraryApi {
        var saved: SaveItineraryRequestDto? = null
        var requestedId: Long? = null

        override suspend fun generate(body: GenerateItineraryRequestDto): GenerateItineraryResponse =
            error("이 테스트는 생성을 부르지 않는다")

        override suspend fun save(body: SaveItineraryRequestDto): SaveItineraryResponseDto {
            saved = body
            return saveResponse
        }

        override suspend fun detail(id: Long): ItineraryDetailDto {
            requestedId = id
            return ApiJson.decodeFromString(ItineraryDetailDto.serializer(), detailJson)
        }

        override suspend fun list(page: Int, size: Int): PageDto<ItinerarySummaryDto> =
            error("이 테스트는 목록을 부르지 않는다")

        override suspend fun delete(id: Long) = error("이 테스트는 삭제를 부르지 않는다")
    }

    // ── 저장 (§5-2) ────────────────────────────────────────────

    @Test
    fun `저장 요청은 생성 응답이 준 조건 snapshot 을 그대로 되돌려 보낸다`() {
        // 조건을 요청값으로 다시 조립하면 서버가 정규화한 값과 어긋난다 (#66 리뷰)
        val result = generated().copy(
            request = ItineraryRequestSnapshot(
                contestId = 153,
                event = "HALF",
                themes = listOf("TOUR", "FOOD"),
                startDate = "2026-08-21",
                endDate = "2026-08-23",
                hotel = HotelSnapshot("호텔 세종 가온", 36.4901, 127.2688),
            ),
        )

        val body = result.toSaveRequest()

        assertEquals(153L, body.contestId)
        assertEquals("HALF", body.event)
        assertEquals(listOf("TOUR", "FOOD"), body.themes)
        assertEquals("2026-08-21", body.startDate)
        assertEquals("2026-08-23", body.endDate)
        assertEquals("호텔 세종 가온", body.hotel?.name)
    }

    @Test
    fun `저장 요청은 5-1 응답 구조 그대로 나간다`() {
        val json = ApiJson.encodeToString(
            GenerateItineraryResponse.serializer(),
            generated().toSaveRequest(),
        )

        // 명세가 "요청 = 5-1 응답 구조" 라고 못박았다. 필드 이름이 하나만 달라도 저장이 깨진다
        assertTrue(json.contains("\"days\""))
        assertTrue(json.contains("\"dayIndex\""))
        assertTrue(json.contains("\"blocks\""))
        assertTrue(json.contains("\"startTime\""))
        assertTrue(json.contains("\"blockType\""))
    }

    @Test
    fun `편집한 블록이 그대로 실린다`() {
        val edited = generated().let { result ->
            val day = result.days.first()
            result.copy(
                days = listOf(
                    day.copy(
                        blocks = day.blocks + ItineraryBlock(
                            id = "blk_0_9",
                            time = "16:00",
                            title = "내가 넣은 카페",
                            catKey = BlockCategory.CAFE,
                            place = Poi("로스터리", 37.5, 127.0, "영등포구 1"),
                            desc = "직접 추가",
                        ),
                    ),
                ),
            )
        }

        val added = edited.toSaveRequest().days.first().blocks.last()

        assertEquals("내가 넣은 카페", added.title)
        assertEquals("CAFE", added.category)
        assertEquals("로스터리", added.placeName)
        assertEquals("USER", added.blockType)
        // 앱이 만든 `blk_0_9` 는 서버 계약에 없는 값이다 — 실으면 안 된다
        assertNull(added.id)
    }

    @Test
    fun `회복일 플래그가 일자와 같은 자리로 실린다`() {
        // 자리로 맞추므로 어긋나면 D+1 회복일이 평일로 저장된다
        val one = generated()
        val two = one.copy(days = one.days + one.days.first(), recoveryFlags = listOf(false, true))

        val body = two.toSaveRequest()

        assertFalse(body.days[0].recovery)
        assertTrue(body.days[1].recovery)
    }

    @Test
    fun `처음 저장이면 교체가 아니다`() = runBlocking {
        val api = FakeApi(saveResponse = SaveItineraryResponseDto(42))

        val outcome = RemoteItineraryRepository(api).save(generated())

        assertEquals(42L, outcome.id)
        // 201 응답에는 replaced 가 없다. 없으면 false 여야 "새로 저장했어요" 가 뜬다
        assertFalse(outcome.replaced)
        assertNotNull(api.saved)
    }

    @Test
    fun `같은 대회 같은 기간이면 교체로 알린다`() = runBlocking {
        val api = FakeApi(saveResponse = SaveItineraryResponseDto(42, replaced = true))

        val outcome = RemoteItineraryRepository(api).save(generated())

        // 새로 담은 것과 덮어쓴 것은 사용자에게 다른 일이다 (§5-2 · SPEC §4.10)
        assertTrue(outcome.replaced)
        assertEquals(42L, outcome.id)
    }

    @Test
    fun `저장하지 않는 구현은 조용히 성공하지 않는다`() {
        // 성공한 척하면 저장 안 된 동선을 저장됐다고 그린다
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { FakeItineraryRepository.save(generated()) }
        }
    }

    // ── 복원 (§5-5) ────────────────────────────────────────────

    @Test
    fun `상세는 저장 시점 snapshot 을 그대로 복원한다`() = runBlocking {
        val api = FakeApi()

        val detail = RemoteItineraryRepository(api).detail(42)

        assertEquals(42L, api.requestedId)
        assertEquals(42L, detail.id)
        assertEquals("2박 3일", detail.result.title)
        assertEquals(153L, detail.result.request.contestId)
        assertEquals("세종특별자치시", detail.region)
        assertEquals(1, detail.result.days.size)
        assertEquals("D-day", detail.result.days.first().label)
    }

    @Test
    fun `복원한 블록은 서버 id 를 쓴다`() = runBlocking {
        val detail = RemoteItineraryRepository(FakeApi()).detail(42)

        // 만들어 낸 id 로 편집하면 서버가 어느 블록인지 못 찾는다 (SPEC §6.3 · §5-5)
        assertEquals(listOf("901", "902"), detail.result.days.first().blocks.map { it.id })
    }

    @Test
    fun `최신 대회는 snapshot 과 따로 온다`() = runBlocking {
        val detail = RemoteItineraryRepository(FakeApi()).detail(42)
        val race = detail.result.days.first().blocks.first()

        assertTrue(detail.needsRegeneration)
        assertEquals("세종마라톤(변경)", detail.contest.name)
        // 서버가 RACE 를 최신 canonical 로 덮어쓰지 않으므로 앱도 덮어쓰지 않는다 —
        // 저장 당시 제목·장소 그대로여야 한다
        assertEquals("🏁 스타트", race.title)
        assertEquals("세종호수공원", race.place?.name)
        assertEquals(BlockType.RACE, race.blockType)
    }

    @Test
    fun `최신 대회가 빠진 상세는 거부한다`() {
        // §5-5 는 `contest` 를 항상 준다. 기본값 null 을 두면 빠진 응답이 정상 상세처럼
        // 통과하고, 화면은 "대회 변경" 안내를 못 그리면서 이유도 모른다 (#202 리뷰)
        val json = DETAIL_JSON.replace(CONTEST_FIELD, "")

        assertThrows(SerializationException::class.java) {
            ApiJson.decodeFromString(ItineraryDetailDto.serializer(), json)
        }
    }

    @Test
    fun `복원한 일자는 서버 id 를 들고 있다`() = runBlocking {
        val detail = RemoteItineraryRepository(FakeApi()).detail(42)

        // 저장 후 편집 API 가 전부 `/days/{dayId}/blocks/...` 다. 이 값을 버리면 화면이
        // 응답을 다시 조회하거나 평행 맵을 만들지 않고는 그 API 를 못 부른다 (#202 리뷰)
        assertEquals(71L, detail.result.days.first().serverId)
    }

    @Test
    fun `생성 응답의 일자에는 서버 id 가 없다`() {
        // 생성(§5-1)은 DB 저장이 없어 id 가 없다. 여기에 값이 생기면 만들어 낸 것이다
        assertNull(generated().days.first().serverId)
    }

    // ── 도구 ──────────────────────────────────────────────────

    /** 생성 응답 하나를 화면 모델로 만들어 둔다. 편집 전 상태다. */
    private fun generated(): ItineraryResult =
        ApiJson.decodeFromString(GenerateItineraryResponse.serializer(), GENERATED_JSON).toResult()

    private companion object {
        const val GENERATED_JSON = """
            {
              "title": "2박 3일",
              "contestId": 153, "event": "HALF", "themes": ["TOUR", "FOOD"],
              "startDate": "2026-08-21", "endDate": "2026-08-23",
              "hotel": { "name": "호텔 세종 가온", "lat": 36.4901, "lng": 127.2688 },
              "recovery": { "label": "D+1 회복 모드", "note": "온천+짧은 산책" },
              "days": [
                {
                  "dayIndex": 0, "date": "2026-08-22", "dayLabel": "D-day",
                  "recovery": false, "note": "완주 후 회복",
                  "blocks": [
                    { "startTime": "09:00", "title": "🏁 스타트", "category": "RACE",
                      "placeName": "세종호수공원", "lat": 36.49, "lng": 127.26,
                      "blockType": "RACE", "systemManaged": true }
                  ]
                }
              ]
            }
        """

        const val CONTEST_FIELD =
            """"contest": { "name": "세종마라톤(변경)", "region": "세종특별자치시", "active": true },"""

        const val DETAIL_JSON = """
            {
              "id": 42,
              "title": "2박 3일",
              "contestId": 153, "event": "HALF", "themes": ["TOUR", "FOOD"],
              "startDate": "2026-08-21", "endDate": "2026-08-23",
              "hotel": { "name": "호텔 세종 가온", "lat": 36.4901, "lng": 127.2688 },
              "recovery": { "label": "D+1 회복 모드", "note": "온천+짧은 산책" },
              "region": "세종특별자치시",
              "needsRegeneration": true,
              "contest": { "name": "세종마라톤(변경)", "region": "세종특별자치시", "active": true },
              "days": [
                {
                  "id": 71,
                  "dayIndex": 0, "date": "2026-08-22", "dayLabel": "D-day",
                  "recovery": false, "note": "완주 후 회복",
                  "blocks": [
                    { "id": 901, "orderNo": 0,
                      "startTime": "09:00", "title": "🏁 스타트", "category": "RACE",
                      "placeName": "세종호수공원", "lat": 36.49, "lng": 127.26,
                      "blockType": "RACE", "systemManaged": true },
                    { "id": 902, "orderNo": 1,
                      "startTime": "12:00", "title": "로컬 점심", "category": "FOOD",
                      "placeName": "골목 손칼국수", "lat": 36.48, "lng": 127.25 }
                  ]
                }
              ]
            }
        """
    }
}
