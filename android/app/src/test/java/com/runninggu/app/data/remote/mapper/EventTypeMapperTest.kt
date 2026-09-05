package com.runninggu.app.data.remote.mapper

import com.runninggu.app.domain.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 종목 표기가 **왕복해도 그대로인가.** (API 명세 부록 C · #213)
 *
 * 앱은 `TEN_K`·`FIVE_K` 인데 서버 계약은 `K10`·`K5` 다. 한쪽만 고치면 저장한 동선을
 * 되살릴 때 종목이 조용히 바뀐다 — 화면에는 아무 오류도 안 뜬다.
 */
class EventTypeMapperTest {

    @Test
    fun `네 종목이 서버 표기로 갔다가 그대로 돌아온다`() {
        // 하나씩 검사하면 K10 ↔ TEN_K 처럼 짝이 어긋난 것을 놓친다 — 왕복으로 본다
        EventType.entries.forEach { event ->
            assertEquals(
                "왕복에서 종목이 바뀐다: $event",
                event,
                eventTypeFromServerName(event.toServerName()),
            )
        }
    }

    @Test
    fun `서버 표기가 계약 그대로다`() {
        // 왕복만 보면 양쪽을 같이 틀려도 통과한다. 계약 문자열 자체를 박아 둔다
        assertEquals("FULL", EventType.FULL.toServerName())
        assertEquals("HALF", EventType.HALF.toServerName())
        assertEquals("K10", EventType.TEN_K.toServerName())
        assertEquals("K5", EventType.FIVE_K.toServerName())
    }

    @Test
    fun `모르는 값은 null 이다`() {
        // 서버가 종목을 늘려도 앱이 죽지 않는다. 무엇으로 대신할지는 부르는 쪽이 정한다
        assertNull(eventTypeFromServerName("K3"))
        assertNull(eventTypeFromServerName("TEN_K"))
        assertNull(eventTypeFromServerName(null))
    }
}
