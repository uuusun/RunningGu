package com.runninggu.app.ui.racedetail

import android.content.Context
import android.content.Intent
import com.runninggu.app.ui.common.openableWebUrl
import com.runninggu.app.ui.model.RaceSummary
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd")

/**
 * S3 [공유] 로 내보낼 본문. (SPEC §4.6 · A4)
 *
 * ## 왜 순수 함수인가
 *
 * `Intent` 를 만드는 자리에 문자열을 끼워 넣으면 **무엇이 나가는지 테스트할 수 없다** —
 * `startActivity` 는 단위 테스트에서 못 부른다. 본문만 떼어 두면 링크가 빠지는 경우,
 * 장소가 없는 경우를 표로 확인할 수 있다.
 *
 * ## 링크는 열리는 것만 넣는다
 *
 * 원천이 `null` 이거나 `javascript:` 같은 것을 주기도 한다. 화면의 [공식 페이지 ↗] 가
 * 이미 [openableWebUrl] 로 거르고 있어서 **같은 기준을 쓴다** — 화면에 버튼이 없는데
 * 공유에는 주소가 실려 나가면 받은 사람이 못 여는 링크를 받는다.
 *
 * ## 앱 이름을 붙이지 않는다
 *
 * "런닝구에서 보내요" 같은 줄은 받는 사람에게 값이 없고, 공유 시트가 이미 어느 앱에서
 * 왔는지 보여준다. 대회 정보만 담는다.
 */
fun raceShareText(race: RaceSummary): String = buildString {
    append(race.name)
    appendLine()
    append("${race.date.format(MONTH_DAY)} ")
    append(race.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN))
    append(" ${race.startTime}")
    // 장소는 비어 있을 수 있다 — 그때 " · " 만 남으면 지저분하다
    if (race.venue.isNotBlank()) append(" · ${race.venue}")
    openableWebUrl(race.officialUrl)?.let {
        appendLine()
        append(it)
    }
}

/**
 * 안드로이드 공유 시트를 띄운다. (SPEC §4.6 · A4)
 *
 * `createChooser` 를 거치는 이유는 **사용자가 매번 고를 수 있게** 하기 위해서다. 빼면
 * 기본 앱이 지정된 기기에서 시트 없이 바로 그 앱으로 넘어가, 문자로 보내려던 사람이
 * 카톡으로 끌려간다.
 */
fun Context.shareRace(race: RaceSummary) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, raceShareText(race))
        // 카톡처럼 제목을 따로 읽는 앱을 위해 넣는다. 본문에는 이미 이름이 들어 있다.
        putExtra(Intent.EXTRA_SUBJECT, race.name)
    }
    startActivity(Intent.createChooser(send, "공유"))
}
