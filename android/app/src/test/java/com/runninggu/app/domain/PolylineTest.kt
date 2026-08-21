package com.runninggu.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Google Encoded Polyline 디코더. (이슈 #62 · API 명세 §6-1)
 *
 * 잘못 풀면 **경로가 엉뚱한 곳에 그려진다** — 10배 어긋나 지도 밖으로 나가거나 한 점에
 * 뭉친다. 눈으로는 "지도가 이상하다" 로만 보여서 원인을 찾기 어렵다. 그래서 표준 벡터로
 * 고정한다.
 */
class PolylineTest {

    private fun assertPoint(expectedLat: Double, expectedLng: Double, actual: LatLng) {
        assertEquals(expectedLat, actual.lat, 1e-5)
        assertEquals(expectedLng, actual.lng, 1e-5)
    }

    @Test
    fun `구글 문서의 표준 벡터를 그대로 푼다`() {
        // Encoded Polyline Algorithm Format 문서의 예시. 구현이 표준과 같은지 고정한다.
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")

        assertEquals(3, points.size)
        assertPoint(38.5, -120.2, points[0])
        assertPoint(40.7, -120.95, points[1])
        assertPoint(43.252, -126.453, points[2])
    }

    @Test
    fun `한국 좌표를 왕복해도 값이 유지된다`() {
        // 여의도 한강공원 근처 3점. 위경도가 둘 다 양수라 부호 처리가 표준 벡터와 다르다.
        val points = Polyline.decode("{b`dFgeueW{DgG{DiG")

        assertEquals(3, points.size)
        assertPoint(37.52510, 126.92580, points[0])
        assertPoint(37.52604, 126.92712, points[1])
        assertPoint(37.52698, 126.92845, points[2])
    }

    @Test
    fun `정밀도 6도 같은 좌표를 준다`() {
        // SPEC §5.8 이 "GraphHopper 응답 확인 후 고정" 으로 열어 둔 자리다. 6 으로 정해져도
        // 부르는 쪽만 바꾸면 되는지 확인한다.
        val points = Polyline.decode("wejqfAo}|aqFwy@oqAwy@crA", precision = 6)

        assertEquals(3, points.size)
        assertPoint(37.52510, 126.92580, points[0])
        assertPoint(37.52698, 126.92845, points[2])
    }

    @Test
    fun `정밀도를 잘못 쓰면 좌표가 어긋난다`() {
        // 5 로 인코딩된 것을 6 으로 풀면 10배 작아진다. 이 테스트는 "정밀도가 계약" 이라는
        // 사실을 고정한다 — 값이 맞아떨어지면 오히려 위험 신호다.
        val wrong = Polyline.decode("{b`dFgeueW{DgG{DiG", precision = 6)

        assertEquals(3, wrong.size)
        assertEquals(3.75251, wrong[0].lat, 1e-5)
    }

    @Test
    fun `빈 문자열은 빈 목록이다`() {
        assertTrue(Polyline.decode("").isEmpty())
    }

    @Test
    fun `잘린 입력은 읽은 데까지만 준다`() {
        // 서버가 잘못 보냈다고 화면이 죽으면 안 된다 (NFR-1·3). 첫 점은 온전하고
        // 두 번째 좌표의 경도가 끊긴 문자열이다.
        val points = Polyline.decode("{b`dFgeueW{D")

        assertEquals(1, points.size)
        assertPoint(37.52510, 126.92580, points[0])
    }

    @Test
    fun `폴리라인이 아닌 문자열은 빈 목록이다`() {
        // 인코딩 문자는 63 이상이다. 그보다 작은 문자가 오면 형식이 아니다.
        assertTrue(Polyline.decode("!!!").isEmpty())
    }
}
