package com.runninggu.app.ui.course

import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 출발지 검색 상태와 문구. (SPEC §4.11-1 ② · §4.11-7) */
class OriginSearchTest {

    @Test
    fun `공백만 있으면 검색을 못 누른다`() {
        assertFalse(OriginSearchState(query = "   ").canSubmit)
        assertFalse(OriginSearchState(query = "").canSubmit)
        assertTrue(OriginSearchState(query = "해운대").canSubmit)
    }

    @Test
    fun `검색 중에는 다시 못 누른다`() {
        assertFalse(OriginSearchState(query = "해운대", searching = true).canSubmit)
    }

    @Test
    fun `못 찾은 것과 못 부른 것을 다르게 적는다`() {
        val noResult = ApiException.Http(404, ApiErrorCode.NO_RESULT, null).searchMessage()
        val network = ApiException.Network(java.io.IOException("boom")).searchMessage()

        assertEquals("그런 장소를 못 찾았어요. 다른 이름으로 찾아보세요.", noResult)
        assertEquals("네트워크에 연결할 수 없어요.", network)
    }
}
