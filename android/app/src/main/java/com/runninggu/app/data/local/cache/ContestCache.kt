package com.runninggu.app.data.local.cache

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.runninggu.app.data.remote.ApiJson
import com.runninggu.app.data.remote.dto.ContestDto

/**
 * 마지막으로 성공한 대회 응답. (SPEC §6.1 · §9.3 · 매핑표 S1·S3 오프라인 · 이슈 #105)
 *
 * **읽기 캐시일 뿐이다.** 서버가 SSOT 고 여기서 서버로 올라가는 것은 없다(AGENTS 2장).
 * 오프라인에서 마지막으로 본 목록을 되살리는 것이 전부다 — 지금은 그 상태에서 화면이
 * 통째로 오류다.
 *
 * ## 왜 DTO 를 통째로 담나
 *
 * 매핑표가 캐시 내용을 **"마지막 성공 DTO, cachedAt"** 으로 적어 뒀다(171행). 컬럼으로
 * 펼치면 서버가 필드를 하나 더 줄 때마다 테이블과 마이그레이션이 따라가야 하는데,
 * 캐시는 그럴 값어치가 없다 — **읽고 나면 네트워크 응답과 똑같이 `toContest()` 로 넘어간다.**
 * 매퍼가 한 벌이라 캐시에서 온 것과 서버에서 온 것이 갈릴 수 없다.
 *
 * 대신 **정렬에 쓰는 [ContestCacheEntity.contestDate] 만 밖으로 꺼냈다.** 오프라인 목록도
 * 서버와 같은 순서로 보여야 하는데, JSON 안에 있으면 SQL 이 정렬을 못 한다.
 */
interface ContestCache {

    /** 성공 응답을 덮어쓴다. 같은 id 는 갱신된다. */
    suspend fun save(contests: List<ContestDto>)

    /** 가까운 대회부터. 서버 목록과 같은 순서다. */
    suspend fun list(limit: Int = DEFAULT_LIMIT): List<ContestDto>

    /** 상세 폴백. 목록에서 본 대회만 있다. */
    suspend fun byId(id: Long): ContestDto?

    /** 로그아웃·계정 전환에서 부른다. 지금은 대회라 계정 데이터가 아니지만 규칙을 맞춰 둔다. */
    suspend fun clear()

    companion object {
        /**
         * 한 번에 되살릴 최대 건수.
         *
         * 오프라인 폴백은 **한 화면 분량**이면 된다. canonical 대회가 153건이라 전부 들고
         * 있어도 되지만, 캐시가 목록 API 를 대신하는 물건처럼 쓰이지 않게 상한을 둔다.
         */
        const val DEFAULT_LIMIT = 100
    }
}

/**
 * `cached_contest` 한 행. (SPEC §6.1 Room 초안)
 *
 * 초안이 "서버 ID·버전·`cachedAt` 을 보존" 이라고 적었는데 **버전은 안 넣었다** — P0 API
 * 계약에 `ETag`·`Last-Modified` 조건부 요청이 없어서 서버가 주는 버전 자체가 없다
 * (#105 에서 확인). 조건부 요청이 생기면 그때 컬럼을 더한다.
 */
@Entity(tableName = "cached_contest")
data class ContestCacheEntity(
    /** canonical `CONTEST.id`. 서버 ID 를 그대로 보존한다. */
    @PrimaryKey val id: Long,
    /** 서버가 준 [ContestDto] 를 그대로 직렬화한 것. */
    val payload: String,
    /** 대회일(`yyyy-MM-dd`). **정렬 전용**이라 화면은 [payload] 안의 값을 쓴다. */
    val contestDate: String,
    /** **앱이 이 응답을 저장한 시각**(epoch millis). 서버가 준 값이 아니다(#105). */
    val cachedAt: Long,
)

@Dao
interface ContestCacheDao {

    @Upsert
    suspend fun upsert(entries: List<ContestCacheEntity>)

    /** 대회일 오름차순 — 목록 API 와 같은 순서다. 같은 날은 id 로 갈라 순서를 고정한다. */
    @Query("SELECT * FROM cached_contest ORDER BY contestDate ASC, id ASC LIMIT :limit")
    suspend fun list(limit: Int): List<ContestCacheEntity>

    @Query("SELECT * FROM cached_contest WHERE id = :id")
    suspend fun byId(id: Long): ContestCacheEntity?

    @Query("DELETE FROM cached_contest")
    suspend fun clear()
}

/** Room 구현. 직렬화 규칙은 네트워크와 같은 [ApiJson] 을 쓴다. */
class RoomContestCache(
    private val dao: ContestCacheDao,
    /** 테스트가 시각을 고정할 수 있게 열어 둔다. */
    private val now: () -> Long = System::currentTimeMillis,
) : ContestCache {

    override suspend fun save(contests: List<ContestDto>) {
        if (contests.isEmpty()) return
        val at = now()
        dao.upsert(
            contests.map {
                ContestCacheEntity(
                    id = it.id,
                    payload = ApiJson.encodeToString(ContestDto.serializer(), it),
                    contestDate = it.contestDate.toString(),  // ISO — 정렬만 하면 된다
                    cachedAt = at,
                )
            },
        )
    }

    override suspend fun list(limit: Int): List<ContestDto> = dao.list(limit).mapNotNull { it.decode() }

    override suspend fun byId(id: Long): ContestDto? = dao.byId(id)?.decode()

    override suspend fun clear() = dao.clear()

    /**
     * 못 읽는 행은 **버린다.** 앱을 올리면서 DTO 가 바뀌면 옛 payload 가 남아 있을 수 있는데,
     * 캐시 한 줄 때문에 화면이 죽는 것보다 그 줄이 없는 편이 낫다. 다음 성공 응답이 덮는다.
     */
    private fun ContestCacheEntity.decode(): ContestDto? =
        runCatching { ApiJson.decodeFromString(ContestDto.serializer(), payload) }.getOrNull()
}
