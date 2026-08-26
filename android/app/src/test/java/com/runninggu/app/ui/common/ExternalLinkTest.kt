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
