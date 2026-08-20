package com.runninggu.app.data.repository

import com.runninggu.app.data.model.NearbyItem
import com.runninggu.app.data.model.SaveCourseResult
import com.runninggu.app.data.model.SavedCourse
import com.runninggu.app.data.model.SavedCourseDetail
import com.runninggu.app.data.remote.SavedCourseApi
import com.runninggu.app.data.remote.apiCall
import com.runninggu.app.data.remote.mapper.toDomain
import com.runninggu.app.data.remote.mapper.toSaveRequest

/**
 * 저장 코스 창구. (API 명세 §7-A · SPEC §4.11-6 · §4.13)
 *
 * **인증이 필요하다.** 게스트가 부르면 `401` 이 `ApiException.Http.needsLogin` 으로 올라온다 —
 * 화면은 로그인 유도를 띄우고 저장을 예약하지 않는다(D-27).
 */
interface SavedCourseRepository {

    /**
     * 내 주변에서 고른 경로를 저장한다. (§7-A)
     *
     * 경로나 원천이 없는 항목은 저장할 수 없어 null 을 돌려준다 — 서버가 geometry 로
     * fingerprint 를 만들기 때문이다(이슈 #62).
     */
    suspend fun save(route: NearbyItem.Route): SaveCourseResult?

    /** 마이 목록. 경로는 안 온다. */
    suspend fun list(page: Int = 0, size: Int = DEFAULT_PAGE_SIZE): SavedCoursePage

    /** 상세 — 경로 점선과 출처를 그린다. */
    suspend fun detail(id: Long): SavedCourseDetail

    suspend fun delete(id: Long)

    companion object {
        /** 개인 목록 기본 페이지 크기 🔒(§0-4). */
        const val DEFAULT_PAGE_SIZE = 20
    }
}

data class SavedCoursePage(
    val courses: List<SavedCourse> = emptyList(),
    val hasNext: Boolean = false,
    val totalElements: Long = 0,
)

/** 서버 구현. */
class RemoteSavedCourseRepository(private val api: SavedCourseApi) : SavedCourseRepository {

    override suspend fun save(route: NearbyItem.Route): SaveCourseResult? {
        val body = route.toSaveRequest() ?: return null
        return apiCall { api.save(body).toDomain() }
    }

    override suspend fun list(page: Int, size: Int): SavedCoursePage = apiCall {
        val dto = api.list(page = page, size = size)
        SavedCoursePage(
            courses = dto.content.map { it.toDomain() },
            hasNext = dto.page.hasNext,
            totalElements = dto.page.totalElements,
        )
    }

    override suspend fun detail(id: Long): SavedCourseDetail = apiCall { api.detail(id).toDomain() }

    override suspend fun delete(id: Long) = apiCall { api.delete(id) }
}
