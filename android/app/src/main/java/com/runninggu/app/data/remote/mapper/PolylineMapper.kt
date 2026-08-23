package com.runninggu.app.data.remote.mapper

import com.runninggu.app.domain.LatLng

/**
 * Google Encoded Polyline 디코더. (API 명세 §6-1 `pathPolyline` · 이슈 #62)
 *
 * 서버는 경로를 좌표 배열이 아니라 **인코딩 문자열 한 줄**로 준다. 지도는 좌표 리스트로
 * 선을 그리므로 앱이 풀어서 넘겨야 한다.
 *
 * **왜 `data/remote` 에 있나** — 서버 와이어 형식(인코딩·정밀도·위경도 순서)을 푸는
 * 일이라 매퍼 경계의 일이다(AGENTS 2장-4 · #129). `domain` 은 풀린 값만 다룬다.
 *
 * **새 라이브러리를 붙이지 않았다.** 표준 알고리즘이라 이만큼이면 되고, 순수 Kotlin 이라
 * 기기 없이 테스트할 수 있다.
 *
 * **푸는 것과 보관하는 것은 다르다.** 지도는 여기서 푸는 좌표를 쓰지만, 저장 요청에는
 * 서버가 준 원문 `pathPolyline` 을 그대로 다시 보낸다 — 풀었다 다시 묶으면 서버의
 * `routeFingerprint` 가 달라져 같은 코스가 중복 저장된다(API 명세 §7-A).
 *
 * 형식은 이슈 #62 에서 **Google Encoded Polyline · precision 5** 로 합의했다. 다만
 * SPEC §5.8 에 "좌표 정밀도는 GraphHopper 실제 응답 확인 후 고정한다" 가 남아 있어
 * [precision] 을 인자로 뺐다 — 6 으로 정해져도 부르는 쪽만 바꾸면 된다.
 */
object Polyline {

    /** 이슈 #62 합의값. 약 1m 해상도로 §3-8 지도 표시에 충분하다. */
    const val DEFAULT_PRECISION = 5

    /**
     * 인코딩 문자열을 좌표열로 푼다.
     *
     * **깨진 입력에 예외를 던지지 않는다.** 경로를 못 그리는 것과 화면이 죽는 것은 다르다 —
     * 서버가 잘못 보냈다고 코스 상세가 통째로 안 열리면 안 된다(NFR-1·3). 도중에 끊기면
     * **거기까지 읽은 좌표를 돌려준다.** 부르는 쪽은 비어 있으면 "경로 없음" 으로 다룬다.
     */
    fun decode(encoded: String, precision: Int = DEFAULT_PRECISION): List<LatLng> {
        if (encoded.isEmpty()) return emptyList()

        val factor = Math.pow(10.0, precision.toDouble())
        val points = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            val dLat = readValue(encoded, index) ?: break
            index = dLat.next
            val dLng = readValue(encoded, index) ?: break
            index = dLng.next

            lat += dLat.value
            lng += dLng.value
            points += LatLng(lat = lat / factor, lng = lng / factor)
        }
        return points
    }

    private class Chunk(val value: Int, val next: Int)

    /**
     * 한 좌표 성분을 읽는다. 5비트씩 모으다 이어짐 비트(`0x20`)가 꺼지면 끝이다.
     *
     * 마지막 조각의 이어짐 비트가 켜진 채 문자열이 끝나면 **잘린 입력**이므로 `null` 이다.
     */
    private fun readValue(encoded: String, start: Int): Chunk? {
        var index = start
        var shift = 0
        var result = 0
        while (index < encoded.length) {
            val chunk = encoded[index].code - 63
            // 유효 문자는 63..126 이다. **상한도 막아야 한다**(#129) — 아래만 막으면
            // `` 같은 문자가 chunk 64 로 들어와 이어짐 비트만 살아 남고 값은 0 이 되어,
            // 폴리라인이 아닌 문자열이 `LatLng(0, 0)` 으로 통과한다.
            if (chunk !in 0..63) return null
            index++
            result = result or ((chunk and 0x1f) shl shift)
            if (chunk < 0x20) {
                // 부호는 지그재그로 실려 온다 — 최하위 비트가 1 이면 음수다
                val value = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                return Chunk(value, index)
            }
            shift += 5
            // 좌표 하나가 32비트를 넘을 수 없다. 넘으면 깨진 입력이다
            if (shift > 30) return null
        }
        return null
    }
}
