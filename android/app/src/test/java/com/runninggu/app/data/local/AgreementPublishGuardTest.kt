package com.runninggu.app.data.local

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 활성 약관에 **내부 표시가 남아 사용자에게 보이는가.** (이슈 #265 · #111 · D-32)
 *
 * ## 왜 필요한가
 *
 * `docs/agreements` 는 저장소 문서이면서 **동시에 앱이 번들해 사용자에게 보여주는 글**이다
 * (`build.gradle.kts` 의 `assets.srcDir`). 하나의 파일이 두 독자를 갖는다.
 *
 * 그래서 팀이 검토하려고 붙여 둔 표시가 그대로 **가입 화면에 뜬다.** 실제로 그랬다 —
 * A2 에서 [이용약관]을 열면 `⚠️ 법률 검토 전 초안`, `⚠️ 미결 — ...`, `(#133 리뷰)` 가
 * 사용자에게 보였다(#265).
 *
 * **눈으로 지우는 것으로는 안 끝난다.** 다음 버전 문안을 쓸 때 또 붙이고, 그 상태로
 * [AgreementDoc.version] 만 올리면 같은 일이 반복된다. 그래서 활성 버전에 대해서만
 * 기계가 막는다.
 *
 * ## 무엇을 막고 무엇을 안 막나
 *
 * **활성 버전만 본다.** [AgreementDoc.version] 이 가리키는 것만이 사용자에게 보인다 —
 * 준비 중인 다음 버전(`v1.1` · `v1.2`)에는 검토 표시가 있어야 정상이고, 여기서 막으면
 * 문안을 쓰는 사람이 표시를 못 쓴다.
 *
 * **표시만 지우는 것으로 통과시키면 안 된다.** `⚠️ 미결 — 보호책임자를 적어야 합니다` 를
 * 지운다고 보호책임자가 정해지는 게 아니다. 이 테스트는 *표시가 남아 있는지*만 보므로,
 * 통과했다고 문안이 완성된 것은 아니다. 미결 값을 실제 결정으로 채우는 것은 #265 의
 * 완료 단위 ①이고 사람이 한다.
 */
class AgreementPublishGuardTest {

    /**
     * 사용자에게 보이면 안 되는 표시들.
     *
     * 이슈·PR 번호(`#133`)를 넣은 이유는, 문안이 **근거를 달고 다니는 습관**으로 쓰이기
     * 때문이다. 마크다운 제목(`# 이용약관`)은 `#` 뒤가 공백이라 안 걸린다.
     */
    private val forbidden = listOf(
        "⚠️" to "검토 표시",
        "미결" to "미결 표시",
        "리뷰 중" to "리뷰 중 표시",
        "법률 검토" to "법률 검토 전 초안 표시",
        "README" to "내부 문서 링크",
    )

    private val issueRef = Regex("""#\d{2,}""")

    private fun read(path: String): String =
        javaClass.classLoader?.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: error("$path 가 번들에 없다")

    @Test
    fun `활성 약관에 내부 표시가 남아 있지 않다`() {
        val violations = buildList {
            AgreementDoc.entries.forEach { doc ->
                val path = AgreementTexts.assetPath(doc)
                read(path).lines().forEachIndexed { index, line ->
                    val hit = forbidden.firstOrNull { (needle, _) -> line.contains(needle) }
                    if (hit != null) {
                        add("$path:${index + 1}  ${hit.second}  |  ${line.trim().take(70)}")
                    } else if (issueRef.containsMatchIn(line)) {
                        add("$path:${index + 1}  이슈·PR 번호  |  ${line.trim().take(70)}")
                    }
                }
            }
        }

        assertTrue(
            buildString {
                appendLine("활성 약관 ${violations.size}곳에 내부 표시가 남아 있다 — 가입 화면에서 사용자에게 보인다 (#265).")
                appendLine("표시만 지우지 말고, 표시가 가리키던 미결 값을 실제 결정으로 채운 뒤 지운다.")
                appendLine()
                violations.forEach { appendLine("  $it") }
            },
            violations.isEmpty(),
        )
    }
}
