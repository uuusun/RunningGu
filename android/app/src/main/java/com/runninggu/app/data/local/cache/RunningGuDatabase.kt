package com.runninggu.app.data.local.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 앱 로컬 DB. (AP-05 · SPEC §6.1 · 이슈 #105)
 *
 * **읽기 캐시 전용이다.** 서버가 SSOT 라 여기 있는 것이 서버로 올라가지 않고, 양방향
 * 동기화도 하지 않는다(AGENTS 2장 · SPEC §9.3).
 *
 * 지금은 `cached_contest` 하나다. `cached_itinerary` · `cached_course` · `cached_favorite`
 * 는 **계정 데이터**라 로그아웃·계정 전환·탈퇴 시 삭제 규칙과 함께 다음 PR 에서 붙인다
 * (#105 결정). 축제는 SPEC §6.1 초안에 테이블이 없어 범위 밖이다.
 */
@Database(entities = [ContestCacheEntity::class], version = 1, exportSchema = true)
abstract class RunningGuDatabase : RoomDatabase() {

    abstract fun contestCache(): ContestCacheDao

    companion object {
        private const val NAME = "runninggu.db"

        /**
         * 파일 DB 를 연다.
         *
         * **마이그레이션을 붙이지 않았다.** 지금은 버전 1 뿐이라 옮길 이전 버전이 없다.
         * 테이블을 바꿀 때는 `schemas/` 에 내보낸 JSON 을 근거로 `Migration` 을 쓴다 —
         * `fallbackToDestructiveMigration()` 을 켜 두면 나중에 계정 캐시가 들어왔을 때
         * 조용히 지워지므로 켜지 않는다.
         */
        fun open(context: Context): RunningGuDatabase =
            Room.databaseBuilder(context.applicationContext, RunningGuDatabase::class.java, NAME)
                .build()
    }
}
