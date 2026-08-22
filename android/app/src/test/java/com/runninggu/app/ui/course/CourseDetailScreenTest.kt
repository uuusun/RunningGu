package com.runninggu.app.ui.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 고도 배열 정규화. (이슈 #100)
 *
 * 처음에 미터 원값을 `ElevationLine` 에 그대로 넘겼더니 `1f - v` 가 크게 음수가 되어
 * 그래프가 캔버스 밖까지 칠해졌다 — 제목·통계·출처가 전부 파랗게 덮였다. 화면을 눌러 보기
 * 전에는 몰랐던 종류의 버그라, 값 변환만이라도 테스트로 못 박아 둔다.
 */
class CourseDetailScreenTest {

    @Test
    fun `최저점은 0 최고점은 1 이 된다`() {
        val result = normalized(listOf(100, 150, 200))

        assertEquals(listOf(0f, 0.5f, 1f), result)
    }

    @Test
    fun `해발이 높아도 0에서 1 사이로 들어온다`() {
        // 대관령 같은 코스는 값이 세 자리다. 그대로 넘어가면 화면이 깨진다.
        val result = normalized(listOf(800, 900, 1010))!!

        assertEquals(3, result.size)
        result.forEach { v ->
            org.junit.Assert.assertTrue("범위를 벗어났다: $v", v in 0f..1f)
        }
    }

    @Test
    fun `평지는 가운데 높이로 편다`() {
        // 0 으로 나누면 NaN 이 되고, 바닥에 붙이면 그래프가 없는 것처럼 보인다.
        val result = normalized(listOf(30, 30, 30))

        assertEquals(listOf(0.5f, 0.5f, 0.5f), result)
    }

    @Test
    fun `점이 모자라면 null 을 주고 시드 프로파일에 맡긴다`() {
        assertNull(normalized(emptyList()))
        assertNull(normalized(listOf(42)))
    }
}
