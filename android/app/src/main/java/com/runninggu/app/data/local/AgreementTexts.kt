package com.runninggu.app.data.local

import android.content.Context
import java.io.IOException

/**
 * A2 가입에서 보여줄 약관 문안. (SPEC §4.2-1 · 이슈 #111 · D-32)
 *
 * ## 왜 앱에 번들하나
 *
 * 서버에 본문을 주는 엔드포인트가 아직 없다(`docs/agreements/README.md` 의 A안). 그래서
 * P0 는 앱이 들고 보여준다. 대신 **원본 파일을 그대로 번들한다** — `build.gradle.kts` 가
 * `docs/agreements` 를 assets 소스로 물려 두어서, 이 파일들과 저장소 문서는 **같은 파일**이다.
 *
 * 사본을 두지 않는 이유가 여기서는 특히 크다. 앱이 보여준 글과 서버가 저장한 버전이
 * 어긋나면 **동의 이력이 무엇에 대한 동의인지 알 수 없다**(NFR-12).
 *
 * ## 버전은 고정이다
 *
 * 가입 요청은 boolean 만 보내고 버전은 서버가 붙인다(§1-5). 그래서 앱이 보여주는 문안과
 * 서버 활성 버전이 **반드시 같아야** 한다 — 어느 한쪽만 올리면 안 된다(D-32 · 이슈 #111).
 * 지금은 양쪽 다 [VERSION] 이다.
 *
 * `v1.1/privacy.md` 도 저장소에 있고 함께 번들되지만 **읽지 않는다.** A2 연결과 서버
 * `PRIVACY=1.1` 전환을 같은 계약으로 맞춘 뒤에 [VERSION] 을 올린다.
 */
object AgreementTexts {

    /** 지금 보여주는 문안 버전. 서버 `runninggu.auth.agreements.*-version` 과 같아야 한다. */
    const val VERSION = "1.0"

    /**
     * 문안을 읽는다. 못 읽으면 null — 화면은 "지금 열 수 없어요" 로 떨어진다.
     *
     * **예외를 던지지 않는다.** 문안을 못 읽는다고 가입 자체를 막으면, 동의 체크는 되는데
     * 내용만 못 보는 상태가 아니라 아무것도 못 하는 상태가 된다.
     */
    fun load(context: Context, doc: AgreementDoc): String? = try {
        context.assets.open(assetPath(doc)).bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        null
    }

    /** `docs/agreements/v1.0/tos.md` 가 assets 에서는 `v1.0/tos.md` 다. */
    fun assetPath(doc: AgreementDoc, version: String = VERSION): String =
        "v$version/${doc.fileName}"
}

/**
 * A2 가 받는 동의 세 가지. (SPEC §4.2-1 · API 명세 §1-5)
 *
 * 서버 `AgreementType` 과 1:1 이다 — 앱이 boolean 세 개를 보내면 서버가 이 순서로
 * `USER_AGREEMENT` 에 버전과 함께 남긴다.
 *
 * @param required 필수인가. 마케팅만 선택이고, **미동의도 정상 가입**이다 —
 *  그때도 `agreed=false` 이력이 남는다(이슈 #111).
 */
enum class AgreementDoc(
    val label: String,
    val fileName: String,
    val required: Boolean,
) {
    TOS("이용약관", "tos.md", required = true),
    PRIVACY("개인정보 수집·이용", "privacy.md", required = true),
    MARKETING("마케팅 정보 수신", "marketing.md", required = false),
    ;

    /** 체크박스에 쓰는 말. "(필수) 이용약관 동의" */
    val checkboxLabel: String
        get() = "(${if (required) "필수" else "선택"}) $label 동의"
}
