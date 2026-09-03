package com.runninggu.app.data.remote.mapper

import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.GenerateItineraryResponse
import com.runninggu.app.data.remote.dto.SaveItineraryRequestDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `POST /api/itineraries` 저장 요청 **본문**이 서버 계약의 필수 필드를 싣는지 본다. (§5-2 🔒 · 이슈 #245)
 *
 * 매퍼만 따로 보던 기존 테스트로는 이 실패를 못 잡았다. `ItineraryResult.toSaveRequest()` 는
 * 멀쩡한 DTO 를 만들었고, **그 DTO 를 JSON 으로 바꾸는 마지막 한 걸음**에서 필드가 사라졌기
 * 때문이다. 그래서 여기서는 DTO 가 아니라 실제로 전선에 나가는 문자열을 본다.
 *
 * 서버 `SaveItineraryRequest` 가 요구하는 것 — 블록의 `startTime`·`title`·`category`·`blockType`
 * 은 `@NotBlank`, 일자의 `blocks` 는 `@NotNull` 이다. 하나라도 빠지면 400 이다.
 *
 * # 망가뜨리면 이것만 실패한다
 * `ApiJson` 의 `encodeDefaults = true` 를 지우면 아래 둘만 실패한다.
 * ```
 * USER 블록도 blockType 을 싣는다 FAILED
 * 블록이 없는 일자도 빈 배열을 싣는다 FAILED
 * ```
 * `explicitNulls = false` 를 함께 지우면 `숙소 없는 동선은 hotel 을 아예 빼고 보낸다` 도 실패한다 —
 * 그쪽은 지금대로 빠지는 것이 맞다.
 */
class SaveItineraryBodyTest {

    /** 서버 생성 응답 그대로. USER 블록 하나 · RACE 블록 하나 · 블록 없는 하루. */
    private val generated = """
      {
        "title": "서울 2박 3일",
        "event": "FULL",
        "contestId": 12,
        "themes": ["TOUR", "FOOD"],
        "startDate": "2026-10-30",
        "endDate": "2026-11-01",
        "recovery": {"label": "회복", "note": "무리하지 않기"},
        "days": [
          {
            "dayIndex": 0, "date": "2026-10-30", "dayLabel": "D-1", "recovery": false, "note": "",
            "blocks": [
              {"startTime":"10:00","title":"경복궁","category":"TOUR","placeName":"경복궁",
               "address":"서울 종로구","lat":37.5796,"lng":126.9770,"description":"",
               "blockType":"USER","systemManaged":false}
            ]
          },
          {
            "dayIndex": 1, "date": "2026-10-31", "dayLabel": "D-day", "recovery": false, "note": "",
            "blocks": [
              {"startTime":"08:00","title":"2026 서울마라톤","category":"RACE","placeName":"광화문",
               "address":"서울 종로구","lat":37.5759,"lng":126.9768,"description":"",
               "blockType":"RACE","systemManaged":true}
            ]
          },
          {
            "dayIndex": 2, "date": "2026-11-01", "dayLabel": "D+1", "recovery": true, "note": "",
            "blocks": []
          }
        ]
      }
    """.trimIndent()

    private fun body(source: String = generated): JsonObject {
        val response = ApiJson.decodeFromString(GenerateItineraryResponse.serializer(), source)
        val request = response.toResult().toSaveRequest()
        val encoded = ApiJson.encodeToString(SaveItineraryRequestDto.serializer(), request)
        return Json.parseToJsonElement(encoded).jsonObject
    }

    private fun JsonObject.day(index: Int) = this["days"]!!.jsonArray[index].jsonObject

    @Test
    fun `USER 블록도 blockType 을 싣는다`() {
        val block = body().day(0)["blocks"]!!.jsonArray[0].jsonObject

        // 기본값이 "USER" 라는 이유로 빠지면 서버 @NotBlank 가 400 을 낸다 (#245)
        assertEquals("USER", block["blockType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `RACE 블록은 종류와 잠금을 그대로 싣는다`() {
        val block = body().day(1)["blocks"]!!.jsonArray[0].jsonObject

        assertEquals("RACE", block["blockType"]?.jsonPrimitive?.content)
        assertEquals(true, block["systemManaged"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `블록이 없는 일자도 빈 배열을 싣는다`() {
        // 서버 DayRequest.blocks 는 @NotNull 이다. 통째로 빠지면 그 하루 때문에 저장 전체가 깨진다
        val blocks = body().day(2)["blocks"]

        assertTrue("blocks 가 통째로 빠졌다", blocks != null)
        assertEquals(0, blocks!!.jsonArray.size)
    }

    @Test
    fun `저장 조건은 생성 응답 snapshot 을 그대로 되비춘다`() {
        val body = body()

        // 요청값으로 다시 조립하지 않는다 — 서버가 정규화했을 수 있다 (#66 리뷰)
        assertEquals(12, body["contestId"]?.jsonPrimitive?.content?.toInt())
        assertEquals("FULL", body["event"]?.jsonPrimitive?.content)
        assertEquals("2026-10-30", body["startDate"]?.jsonPrimitive?.content)
        assertEquals("2026-11-01", body["endDate"]?.jsonPrimitive?.content)
        assertEquals(2, body["themes"]!!.jsonArray.size)
    }

    @Test
    fun `숙소 없는 동선은 hotel 을 아예 빼고 보낸다`() {
        // null 을 빼는 것은 명세가 요구한 생략이다 — 기본값을 빼는 것과 다른 얘기다 (§0-1)
        assertTrue("hotel 이 null 로 실렸다", body()["hotel"] == null)
    }
}
