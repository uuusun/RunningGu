package com.runninggu.app.data.repository

import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.ItineraryApi
import com.runninggu.app.data.remote.dto.BlockCreateRequestDto
import com.runninggu.app.data.remote.dto.BlockCreatedDto
import com.runninggu.app.data.remote.dto.BlockDto
import com.runninggu.app.data.remote.dto.BlockOrderRequestDto
import com.runninggu.app.data.remote.dto.BlockPatchRequestDto
import com.runninggu.app.data.remote.dto.DayBlocksDto
import com.runninggu.app.data.remote.dto.GenerateItineraryRequestDto
import com.runninggu.app.data.remote.dto.GenerateItineraryResponse
import com.runninggu.app.data.remote.dto.ItineraryDetailDto
import com.runninggu.app.data.remote.dto.ItinerarySummaryDto
import com.runninggu.app.data.remote.dto.PageDto
import com.runninggu.app.data.remote.dto.SaveItineraryRequestDto
import com.runninggu.app.data.remote.dto.SaveItineraryResponseDto
import com.runninggu.app.data.remote.httpErrorOf
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.BlockType
import com.runninggu.app.domain.Poi
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * 저장 후 편집 계약. (API 명세 §5-7 ~ §5-10 · 이슈 #213)
 *
 * ## 왜 이 네 개인가
 *
 * S7-R 로 저장 동선을 **열 수는 있는데 고칠 수가 없었다**(#257 은 P0 읽기 전용까지다).
 * 그렇다고 `POST /itineraries` 로 다시 저장하면 안 된다 — 선경님이 #213 에서 짚으셨듯
 * 그 API 는 저장 시점 canonical 대회로 **RACE 블록을 재구성**하므로, USER 장소 하나만
 * 고쳐도 대회 정보가 말없이 바뀐다(SPEC 결정-45).
 *
 * ## 망가뜨리면 이것만 실패한다
 *
 * ```
 * addBlock 의 startTime 기본값을 "09:00" 으로 바꾼다
 *   → 시각을 안 정하면 계약 기본값 13시로 보낸다                FAILED
 *
 * BlockPatch.toDto 에서 안 채운 필드를 현재 값으로 메운다
 *   → 안 건드릴 필드는 아예 안 보낸다                          FAILED
 *
 * toEditedBlock 의 id null 검사를 빼고 만들어 낸 id 를 쓴다
 *   → 편집 응답에 블록 id 가 없으면 올린다                      FAILED
 *
 * toEditedBlock 에서 systemManaged 를 서버 값 그대로 쓴다
 *   → RACE 블록은 서버가 뭐라 하든 잠근다                       FAILED
 *
 * reorderBlocks 응답을 앱에서 다시 정렬한다
 *   → 순서 변경 응답은 서버가 준 순서 그대로 쓴다               FAILED
 * ```
 */
class ItineraryBlockEditTest {

    private class FakeApi(
        private val created: BlockCreatedDto = BlockCreatedDto(blockId = 91, orderNo = 4),
        private val patched: String = PATCHED_BLOCK_JSON,
        private val reordered: String = REORDERED_JSON,
        private val failure: HttpException? = null,
    ) : ItineraryApi {
        var addPath: Triple<Long, Long, BlockCreateRequestDto>? = null
        var patchPath: List<Any>? = null
        var deletePath: List<Long>? = null
        var orderBody: BlockOrderRequestDto? = null

        override suspend fun addBlock(
            itineraryId: Long,
            dayId: Long,
            body: BlockCreateRequestDto,
        ): BlockCreatedDto {
            failure?.let { throw it }
            addPath = Triple(itineraryId, dayId, body)
            return created
        }

        override suspend fun updateBlock(
            itineraryId: Long,
            dayId: Long,
            blockId: Long,
            body: BlockPatchRequestDto,
        ): BlockDto {
            failure?.let { throw it }
            patchPath = listOf(itineraryId, dayId, blockId, body)
            return ApiJson.decodeFromString(BlockDto.serializer(), patched)
        }

        override suspend fun deleteBlock(itineraryId: Long, dayId: Long, blockId: Long) {
            failure?.let { throw it }
            deletePath = listOf(itineraryId, dayId, blockId)
        }

        override suspend fun reorderBlocks(
            itineraryId: Long,
            dayId: Long,
            body: BlockOrderRequestDto,
        ): DayBlocksDto {
            failure?.let { throw it }
            orderBody = body
            return ApiJson.decodeFromString(DayBlocksDto.serializer(), reordered)
        }

        override suspend fun generate(body: GenerateItineraryRequestDto): GenerateItineraryResponse =
            error("이 테스트는 생성을 부르지 않는다")
        override suspend fun save(body: SaveItineraryRequestDto): SaveItineraryResponseDto =
            error("이 테스트는 저장을 부르지 않는다")
        override suspend fun detail(id: Long): ItineraryDetailDto =
            error("이 테스트는 상세를 부르지 않는다")
        override suspend fun list(page: Int, size: Int): PageDto<ItinerarySummaryDto> =
            error("이 테스트는 목록을 부르지 않는다")
        override suspend fun delete(id: Long) = error("이 테스트는 삭제를 부르지 않는다")
    }

    private fun repo(api: ItineraryApi) = RemoteItineraryRepository(api)

    // ── 추가 (§5-7) ────────────────────────────────────────────

    @Test
    fun `시각을 안 정하면 계약 기본값 13시로 보낸다`() = runBlocking {
        // 서버 기본값과 같은 값을 앱도 들고 있어야 화면이 보여 준 시각과 저장된 시각이 안 갈린다
        val api = FakeApi()
        repo(api).addBlock(7, 21, NewBlock(title = "카페", category = BlockCategory.CAFE))

        assertEquals("13:00", api.addPath?.third?.startTime)
    }

    @Test
    fun `추가는 경로에 동선과 일자 id 를 싣는다`() = runBlocking {
        val api = FakeApi()
        val added = repo(api).addBlock(
            itineraryId = 7,
            dayId = 21,
            block = NewBlock(
                title = "국밥",
                category = BlockCategory.FOOD,
                startTime = "18:30",
                place = Poi(name = "소문난 국밥", lat = 37.51, lng = 126.91, addr = "영등포구 9"),
                description = "저녁",
            ),
        )

        assertEquals(7L, api.addPath?.first)
        assertEquals(21L, api.addPath?.second)
        assertEquals("FOOD", api.addPath?.third?.category)
        assertEquals("소문난 국밥", api.addPath?.third?.placeName)
        // 맨 끝에 붙는다 — 화면이 그 자리에 그려야 한다
        assertEquals(91L, added.blockId)
        assertEquals(4, added.orderNo)
    }

    @Test
    fun `장소 없는 블록도 보낼 수 있다`() = runBlocking {
        // 서버가 POI 조회 실패 시 좌표 없는 블록을 주므로(§5-1), 그 모양 그대로 다시 보낼 수 있어야 한다
        val api = FakeApi()
        repo(api).addBlock(7, 21, NewBlock(title = "자유 시간", category = BlockCategory.TOUR))

        val body = api.addPath?.third
        assertNull(body?.placeName)
        assertNull(body?.lat)
        assertNull(body?.address)
    }

    // ── 수정 (§5-8) ────────────────────────────────────────────

    @Test
    fun `안 건드릴 필드는 아예 안 보낸다`() = runBlocking {
        // 보낸 필드만 반영되는 계약이라, 현재 값으로 메워 보내면 그 사이 바뀐 서버 값을 덮는다
        val api = FakeApi()
        repo(api).updateBlock(7, 21, 55, BlockPatch(title = "새 제목"))

        val body = api.patchPath?.get(3) as BlockPatchRequestDto
        assertEquals("새 제목", body.title)
        assertNull("시각을 안 바꿨으면 보내지 않는다", body.startTime)
        assertNull("장소를 안 바꿨으면 보내지 않는다", body.placeName)
        assertNull(body.category)
        assertNull(body.description)
    }

    @Test
    fun `수정 응답은 그 블록으로 갈아끼운다`() = runBlocking {
        val block = repo(FakeApi()).updateBlock(7, 21, 55, BlockPatch(title = "바뀐 카페"))

        assertEquals("55", block.id)
        assertEquals("바뀐 카페", block.title)
        assertEquals(BlockCategory.CAFE, block.catKey)
        assertEquals("로스터리 2호점", block.place?.name)
        assertEquals(BlockType.USER, block.blockType)
    }

    @Test
    fun `편집 응답에 블록 id 가 없으면 올린다`() {
        // 생성 응답 매퍼는 id 가 없으면 blk_0_1 을 만들어 준다. 편집 경로에서 그러면
        // **다음 요청이 그 가짜 id 로 나간다** — 서버가 어느 블록인지 못 찾는다
        val api = FakeApi(patched = """{"startTime":"15:30","title":"카페","category":"CAFE"}""")

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo(api).updateBlock(7, 21, 55, BlockPatch(title = "카페")) }
        }
        assertTrue(thrown.message!!.contains("블록 id"))
    }

    @Test
    fun `RACE 블록은 서버가 뭐라 하든 잠근다`() = runBlocking {
        // systemManaged=false 인데 blockType=RACE 인 응답. 서버 값을 그대로 믿으면 잠금이 풀린다
        val api = FakeApi(
            patched = """
                {"id":9,"startTime":"08:00","title":"스타트","category":"RACE",
                 "blockType":"RACE","systemManaged":false}
            """.trimIndent(),
        )
        val block = repo(api).updateBlock(7, 21, 9, BlockPatch(title = "스타트"))

        assertEquals(BlockType.RACE, block.blockType)
        assertTrue("종류가 RACE 면 systemManaged 다", block.systemManaged)
    }

    @Test
    fun `대회 블록 수정은 409 로 올라온다`() {
        val api = FakeApi(failure = problem(409, "SYSTEM_BLOCK_IMMUTABLE"))

        val thrown = assertThrows(ApiException.Http::class.java) {
            runBlocking { repo(api).updateBlock(7, 21, 9, BlockPatch(title = "못 바꾼다")) }
        }
        // 화면이 "대회 블록은 바꿀 수 없어요" 로 가르려면 code 가 살아 있어야 한다
        assertEquals(ApiErrorCode.SYSTEM_BLOCK_IMMUTABLE, thrown.code)
        assertEquals(409, thrown.status)
    }

    // ── 삭제 (§5-9) ────────────────────────────────────────────

    @Test
    fun `삭제는 세 id 를 경로에 싣는다`() = runBlocking {
        val api = FakeApi()
        repo(api).deleteBlock(7, 21, 55)

        assertEquals(listOf(7L, 21L, 55L), api.deletePath)
    }

    // ── 순서 (§5-10) ───────────────────────────────────────────

    @Test
    fun `순서 변경은 받은 id 목록을 그대로 보낸다`() = runBlocking {
        val api = FakeApi()
        repo(api).reorderBlocks(7, 21, listOf(21L, 19L, 23L))

        // 그 일자의 USER 블록 전체 집합이어야 한다. 앱이 정렬하거나 걸러내면 집합이 어긋난다
        assertEquals(listOf(21L, 19L, 23L), api.orderBody?.blockIds)
    }

    @Test
    fun `순서 변경 응답은 서버가 준 순서 그대로 쓴다`() = runBlocking {
        // 서버가 RACE 를 제자리에 끼워 orderNo 오름차순으로 준다. 앱이 또 정렬하면 규칙이 두 곳이 된다
        val blocks = repo(FakeApi()).reorderBlocks(7, 21, listOf(23L, 21L))

        assertEquals(listOf("23", "9", "21"), blocks.map { it.id })
        assertEquals(BlockType.RACE, blocks[1].blockType)
        assertTrue("응답에 RACE 가 섞여 온다", blocks.any { it.systemManaged })
    }

    @Test
    fun `집합이 어긋나면 400 으로 올라온다`() {
        val api = FakeApi(failure = problem(400, "BLOCK_SET_MISMATCH"))

        val thrown = assertThrows(ApiException.Http::class.java) {
            runBlocking { repo(api).reorderBlocks(7, 21, listOf(21L)) }
        }
        // 재시도로 풀리지 않는다 — 화면이 트리를 다시 읽어야 하는 상황이다
        assertEquals(ApiErrorCode.BLOCK_SET_MISMATCH, thrown.code)
        assertEquals(400, thrown.status)
    }

    // ── 도구 ───────────────────────────────────────────────────

    private fun problem(status: Int, code: String): HttpException {
        val body = """{"code":"$code","title":"t","status":$status,"detail":"d"}"""
        return HttpException(
            Response.error<Any>(status, body.toResponseBody("application/problem+json".toMediaType())),
        )
    }

    private companion object {
        const val PATCHED_BLOCK_JSON = """
            {"id":55,"orderNo":3,"startTime":"15:30","title":"바뀐 카페","category":"CAFE",
             "placeName":"로스터리 2호점","address":"영등포구 11","lat":37.52,"lng":126.92,
             "description":"완주 후 휴식","blockType":"USER","systemManaged":false}
        """

        /** RACE 가 가운데 끼어 오는 응답. 서버가 제자리에 넣어 준다 (§5-10). */
        const val REORDERED_JSON = """
            {"dayId":21,"blocks":[
              {"id":23,"orderNo":1,"startTime":"10:00","title":"오전 관광","category":"TOUR"},
              {"id":9,"orderNo":2,"startTime":"12:00","title":"스타트","category":"RACE",
               "blockType":"RACE","systemManaged":true},
              {"id":21,"orderNo":3,"startTime":"15:30","title":"카페","category":"CAFE"}
            ]}
        """
    }
}
