package com.runninggu.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 약관 문안에서 마크다운 기호만 걷어 내는가. (이슈 #111)
 *
 * **문장이 바뀌면 안 된다.** 사용자가 동의하는 글이라, 읽기 좋게 만드는 것과 내용을
 * 손대는 것은 다르다. 그래서 "기호가 사라졌나" 뿐 아니라 **"문장이 그대로인가"** 도 본다.
 */
class AgreementMarkdownTest {

    @Test
    fun `제목 기호를 걷어 내고 글자는 남긴다`() {
        assertEquals("제1조 (목적)", AgreementMarkdown.toPlainText("## 제1조 (목적)"))
        assertEquals("런닝구 이용약관 (필수)", AgreementMarkdown.toPlainText("# 런닝구 이용약관 (필수)"))
    }

    @Test
    fun `강조 기호를 걷어 낸다`() {
        assertEquals(
            "서비스는 대회를 주최하지 않습니다. 대회 정보는 주최 측이",
            AgreementMarkdown.toPlainText("**서비스는 대회를 주최하지 않습니다.** 대회 정보는 주최 측이"),
        )
    }

    @Test
    fun `구분선은 빈 줄이 된다`() {
        assertEquals("앞\n\n뒤", AgreementMarkdown.toPlainText("앞\n\n---\n\n뒤"))
    }

    @Test
    fun `앱에서 열 수 있는 주소는 남기고 저장소 경로는 글자만 남긴다`() {
        // README 는 APK 에 넣지 않는다(내부 문서) — 주소를 보여 주면 못 여는 곳을 가리킨다.
        assertEquals(
            "법률 검토 전 초안 — README 참고",
            AgreementMarkdown.toPlainText("법률 검토 전 초안 — [README](../README.md) 참고"),
        )
        assertEquals(
            "한국관광공사 (https://kto.visitkorea.or.kr)",
            AgreementMarkdown.toPlainText("[한국관광공사](https://kto.visitkorea.or.kr)"),
        )
    }

    @Test
    fun `표는 한 줄로 읽는다`() {
        val table = """
            | 가입 방식 | 항목 |
            |---|---|
            | 이메일 | 이메일 주소, 비밀번호 |
        """.trimIndent()

        assertEquals(
            "가입 방식 · 항목\n이메일 · 이메일 주소, 비밀번호",
            AgreementMarkdown.toPlainText(table),
        )
    }

    @Test
    fun `실제 문안에서 기호가 사라지고 문장은 남는다`() {
        // 번들된 진짜 파일로 확인한다 — 규칙을 만족하는 가짜 입력만 보면 놓친다.
        val raw = javaClass.classLoader
            ?.getResourceAsStream(AgreementTexts.assetPath(AgreementDoc.TOS))
            ?.bufferedReader()?.use { it.readText() }
        checkNotNull(raw) { "번들에 이용약관이 없다" }

        val plain = AgreementMarkdown.toPlainText(raw)

        assertFalse("제목 기호가 남았다", plain.lineSequence().any { it.startsWith("#") })
        assertFalse("강조 기호가 남았다", plain.contains("**"))
        assertFalse("링크 기호가 남았다", plain.contains("]("))
        // 내용은 그대로다
        assertTrue(plain.contains("제1조"))
        assertTrue(plain.contains("서비스는 대회를 주최하거나 참가 신청을 대행하지 않습니다."))
    }
}
