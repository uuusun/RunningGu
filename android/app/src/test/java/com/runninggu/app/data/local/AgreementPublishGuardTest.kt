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

    /**
     * 이슈·PR 참조. **앞이 영문자+공백이면 이슈 번호가 아니다.**
     *
     * 미국식 주소의 호수 표기가 같은 모양이기 때문이다 — PRIVACY 1.2 위탁표에 들어간
     * Resend 법인 주소 `2261 Market Street #5039` 가 그렇다(#327). 문안 쪽을 `Suite 5039`
     * 로 고치면 **공개 문안이 이 테스트에 종속된다.** 실제 법인 주소라 그럴 자리가 아니다.
     *
     * 마크다운 제목(`# 이용약관`)을 `#` **뒤** 공백으로 거르는 것과 같은 결로, 여기서는
     * `#` **앞** 을 본다. 우리가 쓰는 참조는 전부 한글·괄호·가운뎃점·줄 시작 뒤에 온다.
     *
     * **못 잡는 것** — `PR #133` 처럼 영문 낱말 뒤에 붙인 참조는 통과한다. 활성 문안에
     * 영문으로 이슈를 인용하는 문장이 없어서 받아들인 한계다. 생기면 다시 본다.
     */
    private val issueRef = Regex("""(?<![A-Za-z] )#\d{2,}""")

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

    /**
     * [issueRef] 가 **주소의 호수와 이슈 번호를 가르는지.**
     *
     * 활성 파일만 훑는 테스트와 별개로, 주소의 `#5039`를 이슈 번호로 오인하는 회귀를
     * 고정한다. 다음 약관 버전을 준비할 때도 같은 오탐으로 게시가 막히지 않아야 한다.
     */
    @Test
    fun `주소의 호수는 이슈 번호로 보지 않는다`() {
        val 주소 = listOf(
            // PRIVACY 1.2 위탁표에 실제로 들어간 줄 (#327)
            "| 이전받는 자·연락처 | Plus Five Five, Inc., 2261 Market Street #5039, San Francisco, CA 94114, USA |",
            "Suite #1200, Seattle",
        )
        val 참조 = listOf(
            "> ⚠️ **미결** — 삭제 주기가 정해지지 않았습니다 (#133 리뷰).",
            "이슈 #229 · #230 에서 정한 것",
            "#265 활성화 차단 조건",
        )
        val 제목 = listOf("# 런닝구 이용약관 (필수)", "## 1. 수집하는 항목")

        assertTrue(
            "주소의 호수를 이슈 번호로 잡았다: ${주소.filter { issueRef.containsMatchIn(it) }}",
            주소.none { issueRef.containsMatchIn(it) },
        )
        assertTrue(
            "이슈 참조를 놓쳤다: ${참조.filterNot { issueRef.containsMatchIn(it) }}",
            참조.all { issueRef.containsMatchIn(it) },
        )
        assertTrue(
            "마크다운 제목을 이슈 번호로 잡았다: ${제목.filter { issueRef.containsMatchIn(it) }}",
            제목.none { issueRef.containsMatchIn(it) },
        )
    }
}
