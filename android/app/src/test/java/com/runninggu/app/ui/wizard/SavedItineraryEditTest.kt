package com.runninggu.app.ui.wizard

import com.runninggu.app.data.model.ContestSnapshot
import com.runninggu.app.data.model.HotelSnapshot
import com.runninggu.app.data.model.ItineraryRequestSnapshot
import com.runninggu.app.data.model.ItineraryResult
import com.runninggu.app.data.model.SavedItineraryDetail
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.AddedBlock
import com.runninggu.app.data.repository.BlockPatch
import com.runninggu.app.data.repository.GenerateItineraryRequest
import com.runninggu.app.data.repository.ItineraryRepository
import com.runninggu.app.data.repository.NewBlock
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.ItineraryBlock
import com.runninggu.app.domain.ItineraryDay
import com.runninggu.app.domain.Poi
import com.runninggu.app.ui.OFFLINE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/**
 * 저장 후 편집이 서버로 가는가. (§5-7 ~ §5-10 · #213)
 *
 * ## 왜 로컬 경로와 갈라 보나
 *
 * 화면은 같고 하는 일이 다르다. **로컬만 고치면 화면에서는 바뀌었는데 서버에는 안 가서,
 * 다시 열면 되돌아간다.** 그게 이 화면에서 제일 나쁜 실패다 — 사용자는 고쳤다고 믿는다.
 *
 * ## 망가뜨리면 이것만 실패한다
 *
 * - `onRemoveBlock` 의 `isSavedEditing` 분기를 빼면 → `삭제는 서버로 간다` 만
 * - `reorderBlocks` 에 RACE 를 섞으면 → `순서는 USER 블록만 보낸다` 만
 * - `savedEditMessage` 의 `SYSTEM_BLOCK_IMMUTABLE` 갈래를 빼면 → `대회 블록은 못 고친다고 말한다` 만
 * - `editInFlight` 가드를 빼면 → `보내는 중에는 또 안 보낸다` 만
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedItineraryEditTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeRepo(
        private val failure: Throwable? = null,
        private val reordered: List<ItineraryBlock> = emptyList(),
    ) : ItineraryRepository {
        var deleted: Long? = null; private set
        var reorderIds: List<Long>? = null; private set
        var added: NewBlock? = null; private set
        var patched: Pair<Long, BlockPatch>? = null; private set
        var calls = 0; private set

        override suspend fun detail(id: Long): SavedItineraryDetail = detail42()

        override suspend fun generate(request: GenerateItineraryRequest): ItineraryResult =
            error("이 테스트는 복원만 본다")

        override suspend fun deleteBlock(itineraryId: Long, dayId: Long, blockId: Long) {
            calls++; failure?.let { throw it }; deleted = blockId
        }

        override suspend fun reorderBlocks(
            itineraryId: Long,
            dayId: Long,
            blockIds: List<Long>,
        ): List<ItineraryBlock> {
            calls++; failure?.let { throw it }; reorderIds = blockIds; return reordered
        }

        override suspend fun addBlock(itineraryId: Long, dayId: Long, block: NewBlock): AddedBlock {
            calls++; failure?.let { throw it }; added = block; return AddedBlock(99L, 3)
        }

        override suspend fun updateBlock(
            itineraryId: Long,
            dayId: Long,
            blockId: Long,
            patch: BlockPatch,
        ): ItineraryBlock {
            calls++; failure?.let { throw it }; patched = blockId to patch
            return userBlock("$blockId", "바뀐 곳")
        }
    }

    // ── 픽스처 ──────────────────────────────────────────────

    private suspend fun restored(repo: ItineraryRepository): ResultViewModel =
        ResultViewModel(repository = repo).also { it.restore(42L) }

    @Test
    fun `삭제는 서버로 간다`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = restored(repo)
        advanceUntilIdle()

        vm.onRemoveBlock("11")
        advanceUntilIdle()

        assertEquals(11L, repo.deleted)
        assertTrue("지운 블록이 목록에 남았다", vm.uiState.value.activeDay?.blocks?.none { it.id == "11" } == true)
    }

    // RACE 를 섞으면 서버가 BLOCK_SET_MISMATCH 를 준다 (§5-10). 그 일자의 USER 전체 집합이어야 한다.
    @Test
    fun `순서는 USER 블록만 보낸다`() = runTest(dispatcher) {
        val repo = FakeRepo(reordered = listOf(userBlock("12", "둘"), userBlock("11", "하나")))
        val vm = restored(repo)
        advanceUntilIdle()

        vm.onMoveBlock(from = 0, to = 1)
        advanceUntilIdle()

        assertEquals(listOf(12L, 11L), repo.reorderIds)
        // **앱이 다시 정렬하지 않는다** — 서버가 RACE 를 제자리에 끼워 준 순서를 그대로 쓴다
        assertEquals(listOf("12", "11"), vm.uiState.value.activeDay?.blocks?.map { it.id })
    }

    @Test
    fun `대회 블록은 못 고친다고 말한다`() = runTest(dispatcher) {
        val vm = restored(FakeRepo(failure = httpError(ApiErrorCode.SYSTEM_BLOCK_IMMUTABLE)))
        advanceUntilIdle()

        vm.onRemoveBlock("11")
        advanceUntilIdle()

        assertEquals("대회 일정은 고칠 수 없어요.", vm.uiState.value.editError)
    }

    @Test
    fun `집합이 어긋나면 다시 열라고 말한다`() = runTest(dispatcher) {
        val vm = restored(FakeRepo(failure = httpError(ApiErrorCode.BLOCK_SET_MISMATCH)))
        advanceUntilIdle()

        vm.onMoveBlock(from = 0, to = 1)
        advanceUntilIdle()

        assertEquals("동선이 그 사이 바뀌었어요. 다시 열어 주세요.", vm.uiState.value.editError)
    }

    @Test
    fun `끊겼으면 오프라인 문구를 쓴다`() = runTest(dispatcher) {
        val vm = restored(FakeRepo(failure = ApiException.Network(IOException("끊김"))))
        advanceUntilIdle()

        vm.onRemoveBlock("11")
        advanceUntilIdle()

        assertEquals(OFFLINE, vm.uiState.value.editError)
    }

    // 실패한 채로 다시 누르면 이전 문구가 남아 "방금 것도 실패했나" 로 읽힌다.
    @Test
    fun `다시 시작하면 이전 실패 문구를 지운다`() = runTest(dispatcher) {
        val vm = restored(FakeRepo(failure = ApiException.Network(IOException("끊김"))))
        advanceUntilIdle()
        vm.onRemoveBlock("11")
        advanceUntilIdle()
        assertEquals(OFFLINE, vm.uiState.value.editError)

        val ok = restored(FakeRepo())
        advanceUntilIdle()
        ok.onRemoveBlock("11")
        advanceUntilIdle()
        assertNull(ok.uiState.value.editError)
    }

    // 왕복 중에 같은 행을 또 누르면 두 요청이 엇갈린다.
    @Test
    fun `보내는 중에는 또 안 보낸다`() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = restored(repo)
        advanceUntilIdle()

        vm.onRemoveBlock("11")
        vm.onRemoveBlock("12")   // 첫 요청이 끝나기 전
        advanceUntilIdle()

        assertEquals(1, repo.calls)
    }

    companion object {
        private fun userBlock(id: String, title: String) = ItineraryBlock(
            id = id,
            time = "10:00",
            title = title,
            catKey = BlockCategory.TOUR,
            place = Poi("장소", 37.5, 127.0, "주소"),
            desc = "",
        )

        private fun httpError(code: ApiErrorCode) =
            ApiException.Http(409, code, null)

        private fun detail42() = SavedItineraryDetail(
            id = 42L,
            result = ItineraryResult(
                title = "세종 1박 2일",
                request = ItineraryRequestSnapshot(
                    contestId = 1L,
                    event = "K10",
                    themes = listOf("TOUR"),
                    startDate = "2026-09-04",
                    endDate = "2026-09-05",
                    hotel = HotelSnapshot("호텔", 36.50, 127.25),
                ),
                days = listOf(
                    ItineraryDay(
                        date = LocalDate.parse("2026-09-04"),
                        off = -1,
                        label = "D-1",
                        dateLabel = "09.04",
                        note = "",
                        blocks = listOf(userBlock("11", "하나"), userBlock("12", "둘")),
                        serverId = 7L,
                    ),
                ),
                recovery = null,
                recoveryFlags = listOf(false),
            ),
            region = "세종",
            needsRegeneration = false,
            contest = ContestSnapshot(
                name = "세종 마라톤",
                region = "세종",
                place = "호수공원",
                contestDate = "2026-09-05",
                startTime = "08:00",
                lat = 36.48,
                lng = 127.28,
                active = true,
            ),
        )
    }
}
