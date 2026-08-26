package com.runninggu.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 바깥 웹 페이지를 여는 자리. (SPEC §4.6 📱전환 · AP-11)
 *
 * 대회 [공식 페이지 ↗] 가 유일한 사용처다. 주소는 **크롤한 값**이라 우리가 만든 것이 아니고,
 * 서버도 형식을 보장하지 않는다(`officialUrl: String?`). 그래서 열기 전에 한 번 거른다.
 */

/**
 * 열어도 되는 주소만 통과시킨다.
 *
 * **`http`·`https` 가 아니면 열지 않는다.** 크롤 데이터에 `javascript:` · `intent:` 같은 것이
 * 섞여 들어오면 브라우저가 아니라 **다른 앱이 열리거나 스크립트가 실행될 수 있다.** 대회
 * 공식 페이지는 언제나 웹이라 여기서 막아도 잃는 것이 없다.
 *
 * **접두사로 보지 않고 실제로 파싱한다** (#207 리뷰). `startsWith("https://")` 만 보면
 * `https:///path` · `https://?q=x` 처럼 **호스트가 없는 값**이 통과한다. [HttpUrl] 은 스킴이
 * http/https 인지와 호스트가 있는지를 한 번에 보므로 검사와 구현이 어긋날 자리가 없다.
 *
 * 공백은 파서가 인코딩해 버려서 **파싱 전에** 거른다. 브라우저마다 해석이 갈리는 값을
 * 우리가 임의로 고쳐 주지 않는다.
 *
 * 빈 문자열과 공백만 있는 값도 걸러 낸다 — 번들 매퍼가 `ifBlank { null }` 로 한 번 걸러
 * 주지만(`RaceBundleDto`), 서버 응답은 그 경로를 지나지 않는다.
 *
 * @return 열 수 있으면 다듬은 주소, 아니면 `null`
 */
fun openableWebUrl(raw: String?): String? {
    val url = raw?.trim().orEmpty()
    if (url.isEmpty()) return null
    // 공백이 든 주소는 브라우저마다 다르게 해석한다. 우리가 고쳐 주지 않는다
    if (url.any { it.isWhitespace() }) return null
    // `://` 바로 뒤가 경로·질의·조각이면 **원문에 호스트가 없다.** 파서만 믿을 수 없다 —
    // okhttp 는 브라우저처럼 관대해서 `https:///path` 를 host=`path` 로 읽어 낸다 (#207 리뷰)
    val authority = url.substringAfter("://", missingDelimiterValue = "")
    if (authority.isEmpty() || authority.first() in "/?#") return null
    // 파싱에 실패하면 스킴이 http/https 가 아니거나 주소로 성립하지 않는 것이다
    val parsed = url.toHttpUrlOrNull() ?: return null
    if (parsed.host.isEmpty()) return null
    // **원문을 돌려준다.** 파서가 정규화한 형태(`toString()`)를 쓰면 대회 사이트가 기대하는
    // 경로·질의가 미묘하게 달라질 수 있다. 여기서 하는 일은 고르는 것이지 고치는 것이 아니다
    return url
}

/**
 * Custom Tabs 로 연다. 실패하면 기본 브라우저로 떨어진다.
 *
 * Custom Tabs 는 **앱 안에 머무는 브라우저**라 사용자가 뒤로가기 한 번으로 대회 상세로
 * 돌아온다. 기본 브라우저로 열면 앱이 백그라운드로 밀려 돌아오는 길이 길어진다 — SPEC §4.6
 * 이 굳이 Custom Tabs 를 지정한 이유다.
 *
 * `CustomTabsIntent` 는 결국 `ACTION_VIEW` 라, Custom Tabs 를 모르는 브라우저는 추가 정보를
 * 무시하고 평범하게 연다. 그래서 **따로 분기하지 않아도 대부분 열린다.** 아래 폴백은 브라우저가
 * 하나도 없는 기기용이다.
 *
 * @return 열었으면 `true`. `false` 면 호출부가 사용자에게 알려야 한다
 */
fun openInCustomTab(context: Context, url: String): Boolean {
    val uri = url.toUri()
    return try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, uri)
        true
    } catch (notFound: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (stillNotFound: ActivityNotFoundException) {
            // 브라우저가 없는 기기다. 여기서 크래시로 갚을 자리는 아니다
            false
        }
    }
}
