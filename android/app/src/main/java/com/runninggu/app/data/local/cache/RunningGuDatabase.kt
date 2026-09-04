package com.runninggu.app.data.local.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 앱 로컬 DB. (AP-05 · SPEC §6.1 · 이슈 #105)
 *
 * **읽기 캐시 전용이다.** 서버가 SSOT 라 여기 있는 것이 서버로 올라가지 않고, 양방향
 * 동기화도 하지 않는다(AGENTS 2장 · SPEC §9.3).
 *
 * 지금은 `cached_contest` · `cached_closing_soon` · `cached_closing_soon_meta` 셋이다. `cached_itinerary` ·
 * `cached_course` · `cached_favorite` 는 **계정 데이터**라 로그아웃·계정 전환·탈퇴 시 삭제
 * 규칙과 함께 다음 PR 에서 붙인다(#105 결정). 축제는 SPEC §6.1 에 테이블이 없어 범위
 * 밖이다 — 매핑표 S1 오프라인 행이 "대회·축제" 라고 적고 있었는데 축제 캐시는 만든 적이
 * 없어서 이번에 문서를 코드에 맞췄다(#276).
 */
@Database(
    entities = [
        ContestCacheEntity::class,
        ClosingSoonCacheEntity::class,
        ClosingSoonSnapshotMetaEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class RunningGuDatabase : RoomDatabase() {

    abstract fun contestCache(): ContestCacheDao

    abstract fun closingSoonCache(): ClosingSoonCacheDao

    companion object {
        private const val NAME = "runninggu.db"

        /**
         * `cached_closing_soon` 을 더한다. (#276)
         *
         * **기존 `cached_contest` 는 건드리지 않는다.** 이미 캐시를 받아 둔 사용자가 앱을
         * 올렸다고 오프라인에서 보던 목록을 잃으면 안 된다. 새 테이블은 비어서 시작하고,
         * 다음 성공 응답이 채운다.
         *
         * 컬럼 정의는 [ClosingSoonCacheEntity] 와 **글자 단위로 같아야 한다** — 다르면
         * Room 이 실행 시점에 `IllegalStateException` 으로 막는다(`validateMigration`).
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_closing_soon` (
                        `rank` INTEGER NOT NULL,
                        `contestId` INTEGER NOT NULL,
                        `payload` TEXT NOT NULL,
                        `cachedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`rank`)
                    )
                    """.trimIndent(),
                )
                // snapshot 이 0건이어도 "언제 받았는지" 는 남아야 한다 — 행 수로는
                // "받은 적 없다" 와 "받았는데 0건" 을 못 가른다 (#283 리뷰)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_closing_soon_meta` (
                        `id` INTEGER NOT NULL,
                        `cachedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * 파일 DB 를 연다.
         *
         * 테이블을 바꿀 때는 `schemas/` 에 내보낸 JSON 을 근거로 `Migration` 을 쓴다 —
         * `fallbackToDestructiveMigration()` 을 켜 두면 나중에 계정 캐시가 들어왔을 때
         * 조용히 지워지므로 켜지 않는다.
         */
        fun open(context: Context): RunningGuDatabase =
            Room.databaseBuilder(context.applicationContext, RunningGuDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
