package com.runninggu.app.data.remote.dto

import com.runninggu.app.data.remote.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PATCH .../blocks/{blockId}` 가 **실제로 보내는 본문**. (API 명세 §5-8 · 이슈 #213)
 *
 * ## 왜 DTO 필드가 아니라 JSON 을 보는가
 *
 * 서버는 **필드가 없는 것**과 **명시적 `null`** 을 가른다
 * (`PatchItineraryBlockRequest` 의 `@JsonSetter` + `present` 플래그).
 *
 * ```
 * 필드 없음           → 기존 값 유지
 * "placeName": null  → 장소를 지운다
 * ```
 *
 * 앱의 [BlockPatchRequestDto] 는 안 바꿀 필드를 Kotlin `null` 로 두는데, **그게 본문에서
 * 빠지는지 `null` 로 실리는지는 `ApiJson` 의 `explicitNulls` 에 달려 있다.** 지금은
 * `false` 라 빠지지만, **누가 그 한 줄을 뒤집으면 제목만 바꾸는 요청이 장소를 통째로
 * 지운다.** DTO 필드만 단언하는 테스트로는 못 잡는다 — 객체는 그대로고 와이어만 달라진다.
 *
 * ## 망가뜨리면 이것만 실패한다
 *
 * 실제로 돌려 보고 적는다(2026-09-05). **네 개가 다 깨진다** — 이 파일 전부가 그 한 줄에
 * 매달려 있다는 뜻이고, 그래서 이 테스트가 있어야 한다.
 *
 * ```
 * ApiJson 의 explicitNulls 를 true 로 바꾼다
 *   → 안_바꾸는_필드는_본문에_아예_없다        FAILED
 *     장소를_안_건드리면_placeName_키가_없다    FAILED
 *     보내는_필드만_정확히_실린다               FAILED
 *     빈 문자열은 null 이 아니라 그대로 실린다   FAILED
 * ```
 */
class BlockPatchWireTest {

    private fun json(dto: BlockPatchRequestDto): String =
        ApiJson.encodeToString(BlockPatchRequestDto.serializer(), dto)

    @Test
    fun `안_바꾸는_필드는_본문에_아예_없다`() {
        val body = json(BlockPatchRequestDto(title = "새 제목"))

        assertEquals("""{"title":"새 제목"}""", body)
    }

    @Test
    fun `장소를_안_건드리면_placeName_키가_없다`() {
        // 서버는 "placeName": null 을 **장소 삭제**로 읽는다. 제목만 바꾸려던 요청이
        // 장소를 지우면 사용자는 이유를 모른다.
        val body = json(BlockPatchRequestDto(startTime = "15:30"))

        assertFalse("placeName 키가 있으면 서버가 장소를 지운다", body.contains("placeName"))
        assertFalse(body.contains("address"))
        assertFalse(body.contains("lat"))
    }

    @Test
    fun `보내는_필드만_정확히_실린다`() {
        val body = json(
            BlockPatchRequestDto(
                title = "국밥",
                placeName = "소문난 국밥",
                lat = 37.51,
                lng = 126.91,
            ),
        )

        assertTrue(body.contains(""""title":"국밥""""))
        assertTrue(body.contains(""""placeName":"소문난 국밥""""))
        assertTrue(body.contains(""""lat":37.51"""))
        assertFalse("안 보낸 것은 없어야 한다", body.contains("startTime"))
        assertFalse(body.contains("description"))
    }

    @Test
    fun `빈 문자열은 null 이 아니라 그대로 실린다`() {
        // 서버 normalizeNullable 이 blank → null 로 바꾼다. 앱이 "" 를 보내면
        // 서버는 그 필드를 **지운다** — 안 보낸 것과 다르다(#213 · 건모님 확인).
        val body = json(BlockPatchRequestDto(description = ""))

        assertEquals("""{"description":""}""", body)
    }
}
