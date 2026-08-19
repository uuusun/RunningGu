package com.runninggu.app.data.local

import android.content.Context
import com.runninggu.app.data.model.Contest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 앱에 번들된 대회 초기·오프라인 스냅샷. (SPEC §6.1 · NFR-1)
 *
 * **서버 데이터보다 우선하지 않는다.** 온라인이면 서버 조회가 이기고, 이건 첫 실행과
 * 오프라인일 때만 쓴다. 파일이 낡는 것은 당연하므로 접수 상태는 항상 재계산한다(§5.5).
 */
object ContestBundle {

    const val ASSET_NAME = "races.json"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * assets 에서 읽어 파싱한다. 파일이 없거나 통째로 깨졌으면 [BundleParseResult.empty] 다.
     *
     * 폴백이 실패했다고 앱이 죽으면 안 되므로 예외를 던지지 않는다 — 대신 [BundleParseResult]
     * 에 사유를 남긴다. 부르는 쪽은 비어 있으면 "표시할 대회가 없음" 으로 다룬다.
     */
    fun load(context: Context, assetName: String = ASSET_NAME): BundleParseResult = try {
        context.assets.open(assetName).bufferedReader().use { parse(it.readText()) }
    } catch (e: IOException) {
        BundleParseResult.empty("번들을 읽지 못했다: ${e.message}")
    }

    /** 문자열 파싱만 떼어 둔다 — Android 없이 테스트할 수 있어야 한다. */
    fun parse(raw: String): BundleParseResult = try {
        val rows = json.decodeFromString(ListSerializer(RaceBundleDto.serializer()), raw)
        val contests = rows.mapNotNull { it.toContestOrNull() }
        BundleParseResult(
            contests = contests,
            skipped = rows.size - contests.size,
        )
    } catch (e: SerializationException) {
        BundleParseResult.empty("번들 형식이 계약과 다르다: ${e.message}")
    }
}

/**
 * 번들 파싱 결과.
 *
 * [skipped] 는 날짜가 깨져 버린 항목 수다. 0 이 아니면 번들 생성 쪽이 잘못된 것이므로
 * 조용히 넘기지 않고 남긴다 — 한 건 때문에 전체를 버리지도 않는다.
 */
data class BundleParseResult(
    val contests: List<Contest> = emptyList(),
    val skipped: Int = 0,
    val error: String? = null,
) {
    val isUsable: Boolean get() = contests.isNotEmpty()

    companion object {
        fun empty(error: String) = BundleParseResult(error = error)
    }
}
