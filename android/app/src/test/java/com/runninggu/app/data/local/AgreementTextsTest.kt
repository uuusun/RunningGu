package com.runninggu.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2 가 보여줄 문안이 **실제로 번들돼 있는가.** (이슈 #111 · D-32)
 *
 * `build.gradle.kts` 가 `docs/agreements` 를 assets 소스로 물려 두어서, 여기서 읽는 파일과
 * 저장소 문서는 **같은 파일**이다(`ContestBundleTest` 가 `races.json` 을 그렇게 읽는 것과
 * 같은 방식이다). 사본을 두지 않았으므로 드리프트할 자리가 없다.
 *
 * **이 테스트가 지키는 것은 버전 올림 사고다.** [AgreementDoc.version] 만 올리고 그 버전
 * 폴더에 파일을 안 넣으면, 앱은 "문안을 열지 못했어요" 를 띄우면서도 **동의는 받는다** —
 * 서버는 그 버전으로 이력을 남기고, 사용자는 무엇에 동의했는지 볼 방법이 없다(NFR-12).
 */
class AgreementTextsTest {

    private fun read(path: String): String? =
        javaClass.classLoader?.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }

    @Test
    fun `지금 버전의 문안 세 개가 모두 번들돼 있다`() {
        AgreementDoc.entries.forEach { doc ->
            val path = AgreementTexts.assetPath(doc)
            val text = read(path)
            assertTrue("$path 가 번들에 없다", text != null)
            assertFalse("$path 가 비어 있다", text.isNullOrBlank())
        }
    }

    @Test
    fun `문안의 첫 줄이 그 약관의 제목이다`() {
        // 파일이 뒤바뀌어 들어가는 사고를 막는다 — 셋 다 같은 폴더의 `.md` 라 눈으로는 안 보인다.
        assertTrue(read(AgreementTexts.assetPath(AgreementDoc.TOS))!!.contains("이용약관"))
        assertTrue(read(AgreementTexts.assetPath(AgreementDoc.PRIVACY))!!.contains("개인정보"))
        assertTrue(read(AgreementTexts.assetPath(AgreementDoc.MARKETING))!!.contains("마케팅"))
    }

    @Test
    fun `문안에 적힌 버전과 앱이 쓰는 버전이 같다`() {
        // 문안 본문에 "**버전 1.0**" 이 적혀 있다. 폴더 이름만 바꾸고 본문을 안 고치면
        // 화면에는 새 버전이라 적히는데 글은 옛것인 상태가 된다.
        AgreementDoc.entries.forEach { doc ->
            val text = read(AgreementTexts.assetPath(doc))!!
            assertTrue(
                "${doc.fileName} 본문에 '버전 ${doc.version}' 이 없다",
                text.contains("버전 ${doc.version}"),
            )
        }
    }

    @Test
    fun `필수와 선택이 계약대로다`() {
        // 마케팅만 선택이다. 미동의도 정상 가입이고 서버는 agreed=false 이력을 남긴다(#111).
        assertTrue(AgreementDoc.TOS.required)
        assertTrue(AgreementDoc.PRIVACY.required)
        assertFalse(AgreementDoc.MARKETING.required)
    }

    @Test
    fun `체크박스 문구가 필수 여부를 그대로 말한다`() {
        assertEquals("(필수) 이용약관 동의", AgreementDoc.TOS.checkboxLabel)
        assertEquals("(선택) 마케팅 정보 수신 동의", AgreementDoc.MARKETING.checkboxLabel)
    }

    @Test
    fun `지금 활성인 버전이 셋 다 이것이다`() {
        // **켜는 순간 "아직 안 켰다" 를 지키던 테스트는 목적이 사라진다.** 지우지 않고
        // 바꾼다 — 이제는 활성 버전 세 개를 못으로 박아, 다음에 누가 한쪽만 올리면
        // 여기서 걸리게 한다(#278 리뷰 · 민지).
        //
        // 셋이 서로 다르다. PRIVACY 만 v1.1 을 건너뛰고 v1.2 로 갔다 — 기기 위치 기능이
        // 없어져서다(#215). 앱만 올리고 서버 설정을 안 바꾸면 서버는 옛 버전으로 이력을
        // 남긴다(D-32). 바꿀 때는 `application.yml` 의 세 값과 **같은 PR 에서** 바꾼다.
        assertEquals("1.1", AgreementDoc.TOS.version)
        assertEquals("1.2", AgreementDoc.PRIVACY.version)
        assertEquals("1.1", AgreementDoc.MARKETING.version)
        assertEquals("v1.1/tos.md", AgreementTexts.assetPath(AgreementDoc.TOS))
        assertEquals("v1.2/privacy.md", AgreementTexts.assetPath(AgreementDoc.PRIVACY))
        assertEquals("v1.1/marketing.md", AgreementTexts.assetPath(AgreementDoc.MARKETING))
    }

    @Test
    fun `버전은 약관마다 따로 든다`() {
        // 서버가 tos-version · privacy-version · marketing-version 을 각각 관리하고,
        // 활성 버전도 실제로 갈렸다 — PRIVACY 만 1.2 다. 하나로 묶었다면 PRIVACY 를
        // 1.2 로 올리는 순간 있지도 않은 v1.2/tos.md 를 읽어 깨졌다(#191 리뷰).
        AgreementDoc.entries.forEach { doc ->
            assertEquals("v${doc.version}/${doc.fileName}", AgreementTexts.assetPath(doc))
        }
    }
}
