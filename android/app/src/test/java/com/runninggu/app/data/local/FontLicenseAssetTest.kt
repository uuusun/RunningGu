package com.runninggu.app.data.local

import org.junit.Assert.assertTrue
import org.junit.Test

/** 번들 폰트와 함께 배포해야 하는 OFL 고지 계약. (NFR-18) */
class FontLicenseAssetTest {
    private val text: String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("licenses/OFL-1.1.txt"),
    ) {
        "릴리스 asset에 OFL-1.1.txt가 없습니다"
    }.bufferedReader().use { it.readText() }

    @Test
    fun `번들 폰트 둘의 저작권 고지가 있다`() {
        assertTrue(text.contains("Pretendard Variable 1.3.9"))
        assertTrue(text.contains("Archivo Variable"))
    }

    @Test
    fun `OFL 1점1 전문의 필수 절이 있다`() {
        assertTrue(text.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(text.contains("PERMISSION & CONDITIONS"))
        assertTrue(text.contains("TERMINATION"))
        assertTrue(text.contains("DISCLAIMER"))
    }
}
