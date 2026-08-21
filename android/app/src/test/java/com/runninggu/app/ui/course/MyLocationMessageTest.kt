package com.runninggu.app.ui.course

import com.runninggu.app.data.local.LocationResult
import com.runninggu.app.domain.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [내 위치] 실패 문구. (SPEC §4.11-1 ① · NFR-15)
 *
 * 문구가 틀리면 사용자가 **할 수 없는 일을 계속 시도한다** — 권한을 거부해 놓고
 * "다시 시도" 를 누르는 식이다.
 */
class MyLocationMessageTest {

    @Test
    fun `실패 종류마다 다른 말을 한다`() {
        val messages = listOf(
            LocationResult.PermissionDenied,
            LocationResult.Timeout,
            LocationResult.Unavailable,
        ).map { it.originFailureMessage() }

        assertEquals("문구가 겹친다", messages.size, messages.toSet().size)
    }

    @Test
    fun `권한 거부에는 다시 시도를 권하지 않는다`() {
        // 권한이 없는 채로 다시 눌러 봐야 같은 자리에서 또 실패한다
        val message = LocationResult.PermissionDenied.originFailureMessage()

        assertTrue(message.contains("권한"))
        assertTrue("거부한 사람에게 재시도를 권하고 있다", !message.contains("다시 시도"))
    }

    @Test
    fun `시간 초과에는 권한을 탓하지 않는다`() {
        // 이미 허용한 사람에게 권한 이야기를 하면 할 수 있는 게 없어진다
        val message = LocationResult.Timeout.originFailureMessage()

        assertTrue("허용한 사람에게 권한을 요구하고 있다", !message.contains("권한"))
        assertTrue(message.contains("다시 시도"))
    }

    @Test
    fun `어느 실패든 아래에서 고르라고 함께 알린다`() {
        // 이게 이 화면이 권한 거부에도 동작하는 이유다 (NFR-15)
        listOf(
            LocationResult.PermissionDenied,
            LocationResult.Timeout,
            LocationResult.Unavailable,
        ).forEach { result ->
            assertTrue(
                "$result 에 대체 수단 안내가 없다",
                result.originFailureMessage().contains("골라"),
            )
        }
    }

    @Test
    fun `위치 서비스가 꺼진 것은 권한 문제와 다르게 말한다`() {
        val unavailable = LocationResult.Unavailable.originFailureMessage()
        val denied = LocationResult.PermissionDenied.originFailureMessage()

        assertNotEquals(denied, unavailable)
        assertTrue(unavailable.contains("위치 서비스"))
    }

    @Test
    fun `성공은 실패 문구를 만들지 않는다`() {
        assertEquals("", LocationResult.Found(LatLng(37.5, 127.0)).originFailureMessage())
    }
}
