package com.runninggu.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 대회 [공식 페이지 ↗] 가 **무엇을 열고 무엇을 안 여는가.** (SPEC §4.6 · AP-11)
 *
 * `officialUrl` 은 **크롤한 값**이다. 우리가 만든 것이 아니고 서버도 형식을 보장하지 않는다.
 * 그대로 `ACTION_VIEW` 에 넘기면 브라우저가 아니라 **다른 앱이 열리거나 스크립트가 실행될 수
 * 있다** — `javascript:` · `intent:` 가 그 자리다.
 *
 * 그래서 여는 쪽이 아니라 **거르는 쪽**을 고정한다. 실제로 여는 것은 Custom Tabs 이고 기기가
 * 있어야 확인되지만, "무엇을 열기로 했는가" 는 여기서 끝난다.
 */
class ExternalLinkTest {

    @Test
    fun `https 는 그대로 연다`() {
        assertEquals("https://marathon.example/2026", openableWebUrl("https://marathon.example/2026"))
    }

    @Test
    fun `http 도 연다`() {
        // 대회 공식 페이지에는 아직 평문이 많다. 우리가 막을 자리는 아니다
        assertEquals("http://old-race.example", openableWebUrl("http://old-race.example"))
    }

    @Test
    fun `대문자 스킴도 웹이다`() {
        assertEquals("HTTPS://Race.example", openableWebUrl("HTTPS://Race.example"))
    }

    @Test
    fun `앞뒤 공백은 다듬는다`() {
        assertEquals("https://race.example", openableWebUrl("  https://race.example  "))
    }

    @Test
    fun `웹이 아닌 스킴은 열지 않는다`() {
        // 여기가 이 파일의 이유다. 크롤 데이터에 섞여 들어오면 브라우저가 안 열린다
        assertNull(openableWebUrl("javascript:alert(1)"))
        assertNull(openableWebUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(openableWebUrl("file:///sdcard/x.html"))
        assertNull(openableWebUrl("market://details?id=com.x"))
    }

    @Test
    fun `스킴이 없으면 열지 않는다`() {
        // "race.example" 은 브라우저마다 검색어로도 주소로도 읽힌다. 우리가 고쳐 주지 않는다
        assertNull(openableWebUrl("race.example"))
        assertNull(openableWebUrl("www.race.example"))
    }

    @Test
    fun `스킴만 있고 호스트가 없으면 열지 않는다`() {
        assertNull(openableWebUrl("https://"))
        assertNull(openableWebUrl("http://"))
    }

    /**
     * 접두사만 보던 시절에 통과하던 것들이다. (#207 리뷰)
     *
     * `startsWith("https://")` 뒤에 글자만 있으면 됐기 때문에, **호스트 자리에 경로·질의·
     * 조각이 바로 붙은 값**이 "열 수 있는 주소" 로 판정됐다. 브라우저는 자기 오류 페이지를
     * 띄울 뿐이라 위험하진 않지만, **크롤 값의 안전 경계라고 설명한 함수가 그 값을 통과시키면
     * 다음 사람이 이 함수를 믿을 수 없게 된다.**
     */
    @Test
    fun `호스트 자리가 비어 있으면 열지 않는다`() {
        assertNull(openableWebUrl("https:///path"))
        assertNull(openableWebUrl("https://?q=x"))
        assertNull(openableWebUrl("https://#fragment"))
        assertNull(openableWebUrl("http://?q=1"))
        assertNull(openableWebUrl("https:////"))
    }

    @Test
    fun `호스트가 있으면 경로 질의 조각이 붙어도 연다`() {
        // 위 테스트가 호스트 있는 정상 주소까지 막아 버리면 링크가 사라진다
        assertEquals("https://race.example/a?b=1#c", openableWebUrl("https://race.example/a?b=1#c"))
        assertEquals("https://race.example:8443/x", openableWebUrl("https://race.example:8443/x"))
    }

    @Test
    fun `원문을 그대로 돌려준다`() {
        // 파서가 정규화한 형태로 바꾸면 대회 사이트가 기대하는 주소와 미묘하게 달라질 수 있다.
        // 여기서 하는 일은 고르는 것이지 고치는 것이 아니다
        val raw = "http://mara1080.com/event/48116855-ac2a-4db5-b343-593a01c5b353"
        assertEquals(raw, openableWebUrl(raw))
    }

    @Test
    fun `가운데 공백이 있으면 열지 않는다`() {
        // 브라우저마다 인코딩이 달라 어디로 갈지 우리가 모른다
        assertNull(openableWebUrl("https://race.example/a b"))
    }

    @Test
    fun `비어 있으면 열지 않는다`() {
        assertNull(openableWebUrl(null))
        assertNull(openableWebUrl(""))
        assertNull(openableWebUrl("   "))
    }
}
