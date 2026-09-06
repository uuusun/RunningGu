package com.runninggu.app.data.repository

import com.runninggu.app.data.remote.dto.DEFAULT_BLOCK_START_TIME
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.Poi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 저장 후 편집 요청을 **보내기 전에** 서버와 같은 값으로 맞춘다. (API 명세 §5-7 · §5-8 · #213)
 *
 * ## 왜 필요한가
 *
 * §5-7 응답은 `{blockId, orderNo}` 뿐이라 **앱은 방금 보낸 값으로 행을 그린다.** 서버는
 * 저장하면서 앞뒤 공백을 지우고 공백만 있는 값을 `null` 로 바꾸므로, 정규화를 안 하면
 * 화면에 남은 공백이 저장된 값과 어긋난다.
 *
 * 응답을 블록 전체로 넓히는 대신 앱이 맞추는 쪽으로 정해졌다(2026-09-06 선경 결정 · #301).
 *
 * ## 망가뜨리면 이것만 실패한다
 *
 * 실제로 되돌려 돌려 보고 적는다.
 *
 * ```
 * NewBlock.toDto 에서 normalized() 를 뺀다
 *   → 추가는_앞뒤_공백을_지우고_보낸다                     FAILED
 *     공백만_있는_장소와_설명은_null_로_나간다             FAILED
 *
 * BlockPatch.normalized 의 startTime 에 기본값을 채운다
 *   → 수정은_시각을_기본값으로_채우지_않는다                FAILED
 * ```
 */
class BlockRequestNormalizeTest {

    private fun poi(name: String, addr: String) = Poi(name = name, lat = 37.5, lng = 127.0, addr = addr)

    @Test
    fun `추가는_앞뒤_공백을_지우고_보낸다`() {
        val dto = NewBlock(
            title = "  국밥집  ",
            category = BlockCategory.FOOD,
            place = poi("  소문난 국밥  ", "  서울 중구 1  "),
            description = "  점심  ",
        ).toDto()

        assertEquals("국밥집", dto.title)
        assertEquals("소문난 국밥", dto.placeName)
        assertEquals("서울 중구 1", dto.address)
        assertEquals("점심", dto.description)
    }

    @Test
    fun `공백만_있는_장소와_설명은_null_로_나간다`() {
        // 서버 normalizeNullable 과 같은 결과여야 한다. "" 를 그대로 보내면 서버는
        // 그 값을 지우는데, 화면은 "" 를 들고 있어 저장된 값과 갈린다.
        val dto = NewBlock(
            title = "산책",
            category = BlockCategory.NATURE,
            place = poi("   ", "\u3000"),
            description = "\t\n",
        ).toDto()

        assertNull(dto.placeName)
        assertNull(dto.address)
        assertEquals("", dto.description)
    }

    @Test
    fun `빈_제목은_요청을_만들지_않는다`() {
        // 서버는 400 VALIDATION_FAILED 다. 요청을 보내고 나서 실패를 받는 것보다
        // 만들 때 막는 편이 화면이 왜 실패했는지 알기 쉽다.
        val blank = NewBlock(title = "   ", category = BlockCategory.FOOD)

        assertThrows(IllegalArgumentException::class.java) { blank.toDto() }
        assertThrows(IllegalArgumentException::class.java) { BlockPatch(title = " ").toDto() }
    }

    @Test
    fun `추가만_시각_기본값을_가진다`() {
        val added = NewBlock(title = "국밥", category = BlockCategory.FOOD, startTime = "  ").toDto()

        assertEquals(DEFAULT_BLOCK_START_TIME, added.startTime)
    }

    @Test
    fun `수정은_시각을_기본값으로_채우지_않는다`() {
        // §5-8 은 생략하면 기존 시각 유지다. 여기서 13:00 을 채우면 사용자가 고르지
        // 않은 시각이 말없이 저장된다.
        val patch = BlockPatch(title = "국밥").toDto()

        assertNull(patch.startTime)
        assertThrows(IllegalArgumentException::class.java) { BlockPatch(startTime = " ").toDto() }
    }

    @Test
    fun `수정도_같은_정규화를_거친다`() {
        val dto = BlockPatch(
            title = " 국밥 ",
            place = poi(" 소문난 국밥 ", "   "),
            description = " 저녁 ",
        ).toDto()

        assertEquals("국밥", dto.title)
        assertEquals("소문난 국밥", dto.placeName)
        assertNull("공백만 있는 주소는 서버가 null 로 저장한다", dto.address)
        assertEquals("저녁", dto.description)
    }

    /**
     * **앱이 정규화한 값은 서버 정규화를 한 번 더 통과해도 안 바뀐다.**
     *
     * 이것이 실제로 지켜야 하는 성질이다. §5-7 응답에 저장된 문자열이 없어서 앱은 보낸
     * 값으로 행을 그리는데, 서버가 그 값을 또 깎으면 화면과 저장값이 갈린다.
     *
     * 말로 적어 두면 다음 사람이 확인할 수 없어서 실제 `java.lang.String` 의 메서드를
     * 불러 대조한다.
     */
    @Test
    fun `앱이_정규화한_값은_서버를_통과해도_안_바뀐다`() {
        val strip = String::class.java.getMethod("strip")

        for (sample in UNICODE_SAMPLES) {
            val sent = sample.trim()
            assertEquals(
                "서버가 앱이 보낸 값을 또 깎으면 화면과 저장값이 갈린다: ${sample.map { it.code }}",
                sent,
                strip.invoke(sent) as String,
            )
        }
    }

    /**
     * **앱이 서버보다 조금 더 지운다 — `NBSP`(U+00A0) 한 종류다.**
     *
     * Kotlin `Char.isWhitespace()` 는 `Character.isWhitespace` **또는** `isSpaceChar` 인데
     * Java `strip()` 은 앞의 것만 본다. 그래서 `NBSP` 는 앱이 지우고 서버는 남긴다.
     *
     * 더 지우는 쪽이라 위 성질은 깨지지 않는다(앱이 지운 값에 서버가 할 일이 없다).
     * 다만 **`NBSP` 만으로 된 제목은 앱이 먼저 거절한다** — 서버라면 통과시킬 값이다.
     * 웹 폼에서 붙여넣기로 들어오는 문자라 실제로 마주칠 수 있어서 적어 둔다.
     */
    @Test
    fun `NBSP_는_앱이_더_지운다`() {
        val strip = String::class.java.getMethod("strip")
        val nbsp = "\u00A0국밥\u00A0"

        assertEquals("서버는 NBSP 를 남긴다", nbsp, strip.invoke(nbsp) as String)
        assertEquals("앱은 NBSP 까지 지운다", "국밥", nbsp.trim())

        assertThrows(IllegalArgumentException::class.java) {
            NewBlock(title = "\u00A0", category = BlockCategory.FOOD).toDto()
        }
    }

    private companion object {
        val UNICODE_SAMPLES = listOf(
            " 국밥 ",
            "\u3000국밥\u3000",   // 전각 공백
            "\u2003국밥\u2003",   // EM space
            "\t\n국밥 \r",
            "\u00A0국밥\u00A0",   // NBSP
            "\u3000",
            "\u00A0",
            "",
        )
    }
}
