package com.runninggu.app.data.local.cache

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.ContestDto
import java.time.Duration

/**
 * 마지막으로 성공한 **홈 마감임박** 응답. (SPEC §6.1 `cached_closing_soon` · 매핑표 S1 오프라인 · 이슈 #276)
 *
 * ## 왜 [ContestCache] 를 재사용하지 않나
 *
 * `cached_contest` 에도 같은 대회가 들어 있어서 거기서 골라 쓸 수 있을 것 같지만,
 * **마감임박은 서버가 고르고 서버가 순서를 정하는 목록이다.** 앱이 캐시에서 다시 고르면
 * 그 선정 규칙(접수중 ∧ 마감 임박순 상위 4건)을 앱이 구현하는 셈이 되고, 서버가 규칙을
 * 바꾸면 온라인과 오프라인이 다른 목록을 보여준다.
 *
 * 그래서 **고른 결과와 순서를 통째로** 담는다. 앱이 하는 일은 날짜 차이를 세는 것뿐이다.
 *
 * ## `dDayApply` 를 저장하지 않는 이유
 *
 * 목록·상세 캐시와 결정적으로 다른 점이다.
 *
 * ```
 * 대회 날짜 10.03   →  사흘 뒤에 꺼내도 10.03      안 낡는다
 * 마감 D-4         →  사흘 뒤에 꺼내면 여전히 D-4   낡는다
 * ```
 *
 * **이미 마감된 대회를 "마감 D-2" 로 보여주는 것은 안 보여주는 것보다 나쁘다** —
 * 사용자가 신청하러 갔다가 헛걸음한다. 그래서 `applyEnd` 만 담고 꺼낼 때 오늘(KST)로
 * 다시 센다. 계산은 [com.runninggu.app.domain.dDay] 가 이미 갖고 있다.
 */
interface ClosingSoonCache {

    /**
     * 성공 응답을 **snapshot 단위로 원자 교체**한다.
     *
     * 행 단위 upsert 가 아니다 — 지난 응답의 5번째가 남아 있으면 서버가 주지 않은 항목이
     * 목록에 섞인다. 마감임박은 "서버가 고른 넷" 이라 그 넷이 통째로 한 단위다.
     *
     * **정상 빈 응답(0건)도 저장한다.** 예외를 두면 서버가 목록에서 뺀 대회가 오프라인에서
     * 되살아난다 — 접수가 다 끝나 서버가 0건을 줬는데, 앱은 어제의 넷을 그대로 보여준다.
     * "성공 응답을 통째로 바꾼다" 는 계약에 빈 응답 예외는 없다(#283 리뷰).
     */
    suspend fun save(contests: List<ContestDto>)

    /**
     * 유효한 snapshot. **없으면 빈 목록이 아니라 `null` 이다.**
     *
     * 둘을 갈라야 하는 이유는 호출부가 하는 일이 다르기 때문이다 — `null` 은 "되살릴 게
     * 없다"(원래 네트워크 오류를 그대로 던진다), 빈 목록은 "되살렸는데 보여줄 게 없다"
     * (정상 빈 상태다). 뭉뚱그리면 오프라인 오류가 "대회가 없음" 으로 둔갑한다.
     *
     * [MAX_AGE] 가 지난 snapshot 은 없는 것으로 친다.
     *
     * **0건짜리 유효한 snapshot 은 `null` 이 아니다.** 서버가 마지막으로 "없다" 고 답한
     * 것도 성공 응답이라, 그때 오프라인이면 화면은 [다시 시도] 가 아니라 정상 빈 상태를
     * 그려야 한다. 그래서 행이 없어도 저장 시각([ClosingSoonSnapshotMetaEntity])이 남아
     * 있으면 살아 있는 snapshot 으로 본다.
     */
    suspend fun snapshot(): ClosingSoonSnapshot?

    /** 로그아웃·계정 전환에서 부른다. 계정 데이터는 아니지만 규칙을 [ContestCache] 와 맞춘다. */
    suspend fun clear()

    companion object {
        /**
         * snapshot 을 살려 두는 기간. **24시간**이다. (이슈 #276 결정)
         *
         * 접수 마감이 안 지났어도 일주일 전 목록을 보여주는 것은 다른 문제다 — 그 사이
         * 서버에서 대회가 비활성으로 바뀌었을 수 있고(결정-46 `active=false`), 마감임박은
         * 성격상 **매일 바뀌는 목록**이라 오래 들고 있을 값어치가 없다.
         */
        val MAX_AGE: Duration = Duration.ofHours(24)
    }
}

/**
 * 되살린 마감임박 한 벌.
 *
 * [cachedAt] 을 함께 주는 이유는 **화면이 마지막 성공본임을 말할 수 있어야 하기 때문**이다
 * (SPEC §6.1 캐시 출처 표기). 언제 것인지 모르는 채로 낡은 목록을 그리면, 사용자는 지금
 * 서버가 말하는 것과 다른 화면을 보면서 그 사실을 알 방법이 없다.
 *
 * @param contests 서버가 준 순서 그대로. `dDayApply` 는 들어 있지 않다 — 호출부가 센다.
 * @param cachedAt 앱이 이 응답을 저장한 시각(epoch millis · UTC).
 */
data class ClosingSoonSnapshot(
    val contests: List<ContestDto>,
    val cachedAt: Long,
)

/**
 * `cached_closing_soon` 한 행. (SPEC §6.1)
 *
 * **키가 [rank] 다.** 대회 id 가 아니다 — 이 테이블이 보존하는 것은 "어떤 대회인가" 가
 * 아니라 **"서버가 몇 번째로 줬는가"** 이기 때문이다. 같은 대회가 목록에서 자리를 옮기면
 * 그건 다른 행이다.
 */
@Entity(tableName = "cached_closing_soon")
data class ClosingSoonCacheEntity(
    /** 서버 응답에서의 순서(0부터). 정렬 키이자 기본 키다. */
    @PrimaryKey val rank: Int,
    /** canonical `CONTEST.id`. 화면은 [payload] 안의 값을 쓰고 이 컬럼은 조회 편의용이다. */
    val contestId: Long,
    /** 서버가 준 [ContestDto] 를 그대로 직렬화한 것. */
    val payload: String,
    /** **앱이 이 응답을 저장한 시각**(epoch millis · UTC). 서버가 준 값이 아니다. */
    val cachedAt: Long,
)

/**
 * snapshot 한 벌의 머리말. **행이 0개여도 남는다.** (#283 리뷰)
 *
 * 이것이 없으면 "마감임박을 받은 적이 없다" 와 "받았는데 0건이었다" 를 가를 수 없다.
 * 앞엣것은 오프라인에서 네트워크 오류 + [다시 시도] 고, 뒤엣것은 정상 빈 상태다 —
 * 사용자가 할 일이 다르다.
 */
@Entity(tableName = "cached_closing_soon_meta")
data class ClosingSoonSnapshotMetaEntity(
    /** 언제나 0. 이 표에는 **한 줄만** 산다 — snapshot 은 한 번에 하나다. */
    @PrimaryKey val id: Int = 0,
    /** 앱이 이 응답을 저장한 시각(epoch millis · UTC). */
    val cachedAt: Long,
)

@Dao
interface ClosingSoonCacheDao {

    /**
     * 지우고 넣는다. **한 트랜잭션이다.**
     *
     * 중간에 실패해서 빈 테이블이 남으면 오프라인에서 보여줄 것이 사라진다. 여기서
     * 트랜잭션을 안 걸면 "새 응답을 저장하다 죽어서 옛 목록까지 잃는" 경우가 생긴다.
     */
    @Transaction
    suspend fun replaceAll(meta: ClosingSoonSnapshotMetaEntity, entries: List<ClosingSoonCacheEntity>) {
        clear()
        insert(entries)
        upsertMeta(meta)
    }

    @Insert
    suspend fun insert(entries: List<ClosingSoonCacheEntity>)

    @Query("SELECT * FROM cached_closing_soon ORDER BY rank ASC")
    suspend fun all(): List<ClosingSoonCacheEntity>

    @Query("DELETE FROM cached_closing_soon")
    suspend fun clear()

    @Upsert
    suspend fun upsertMeta(meta: ClosingSoonSnapshotMetaEntity)

    @Query("SELECT * FROM cached_closing_soon_meta WHERE id = 0")
    suspend fun meta(): ClosingSoonSnapshotMetaEntity?

    @Query("DELETE FROM cached_closing_soon_meta")
    suspend fun clearMeta()
}

/** Room 구현. 직렬화 규칙은 네트워크와 같은 [ApiJson] 을 쓴다. */
class RoomClosingSoonCache(
    private val dao: ClosingSoonCacheDao,
    /** 테스트가 시각을 고정할 수 있게 열어 둔다. */
    private val now: () -> Long = System::currentTimeMillis,
) : ClosingSoonCache {

    override suspend fun save(contests: List<ContestDto>) {
        val at = now()
        dao.replaceAll(
            meta = ClosingSoonSnapshotMetaEntity(cachedAt = at),
            entries = contests.mapIndexed { index, dto ->
                ClosingSoonCacheEntity(
                    rank = index,
                    contestId = dto.id,
                    // **`dDayApply` 를 떼고 담는다.** 남겨 두면 "저장하지 않는다" 는 계약이
                    // 글자로만 참이 되고, 다음 사람이 payload 를 열어 그 값을 쓸 수 있다.
                    // 담지 않으면 애초에 쓸 수가 없다 (#283 리뷰)
                    payload = ApiJson.encodeToString(ContestDto.serializer(), dto.copy(dDayApply = null)),
                    cachedAt = at,
                )
            },
        )
    }

    override suspend fun snapshot(): ClosingSoonSnapshot? {
        // **행이 아니라 meta 가 snapshot 의 존재를 말한다.** 행 수로 판단하면 "받은 적 없다"
        // 와 "받았는데 0건이었다" 가 같아진다
        val cachedAt = dao.meta()?.cachedAt ?: return null
        if (now() - cachedAt >= ClosingSoonCache.MAX_AGE.toMillis()) return null

        val rows = dao.all()
        val contests = rows.mapNotNull { it.decode() }
        // 담긴 게 있었는데 **전부 못 읽으면** 되살릴 것이 없는 것과 같다. 빈 목록으로 주면
        // 읽기 실패가 "대회가 없음" 으로 둔갑한다 — 0건으로 저장된 것과는 다르다
        if (rows.isNotEmpty() && contests.isEmpty()) return null
        return ClosingSoonSnapshot(contests, cachedAt)
    }

    override suspend fun clear() {
        dao.clear()
        dao.clearMeta()
    }

    /** 못 읽는 행은 버린다. 이유는 [RoomContestCache] 와 같다. */
    private fun ClosingSoonCacheEntity.decode(): ContestDto? =
        runCatching { ApiJson.decodeFromString(ContestDto.serializer(), payload) }.getOrNull()
}
