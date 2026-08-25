package com.runninggu.app.data.local

/**
 * 약관 문안의 마크다운 기호를 걷어 낸다. (이슈 #111)
 *
 * 원본은 저장소 문서라 마크다운이다. 그대로 띄우면 화면에 `## 제1조` · `**버전 1.0**` ·
 * `[README](../README.md)` 가 보인다 — **사용자가 동의하는 글**이라 읽기 나쁜 것을 그냥
 * 둘 수 없다.
 *
 * ## 무엇을 지우고 무엇을 남기나
 *
 * **문장은 한 글자도 건드리지 않는다.** 지우는 것은 마크다운 *기호*뿐이다. 렌더러를 넣지
 * 않은 이유도 같다 — 새 의존성이기도 하지만, 표가 접히거나 강조가 사라지는 식으로 **표시가
 * 원문과 달라질 여지**를 만들고 싶지 않다.
 *
 * 링크는 `[런닝구](https://…)` → `런닝구 (https://…)` 로 주소를 남긴다. 다만 저장소 안을
 * 가리키는 상대 경로(`../README.md`)는 앱에서 열 수 없으므로 **글자만 남긴다** — 있지도
 * 않은 곳을 가리키는 주소를 보여 주면 더 헷갈린다.
 */
object AgreementMarkdown {

    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+""")
    private val THEMATIC_BREAK = Regex("""^\s{0,3}(-{3,}|\*{3,}|_{3,})\s*$""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)
    private val LINK = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    private val TABLE_ROW = Regex("""^\s*\|.*\|\s*$""")
    private val TABLE_DIVIDER = Regex("""^\s*\|[\s|:-]+\|\s*$""")

    /**
     * 인용 표시. **다른 판정보다 먼저 걷어 낸다.** (#191 리뷰)
     *
     * `privacy.md` 의 보유기간 표가 `> | 기록 | 탈퇴하면 | …` 처럼 인용문 **안에** 있다.
     * 줄이 `|` 로 시작할 때만 표로 보면 이런 줄이 규칙을 통째로 비켜 가서, 화면에
     * `> |---|---|---|` 가 그대로 남는다.
     *
     * 평문에는 인용을 나타낼 방법이 없으므로 표시 자체도 지운다 — `>` 만 남으면 노이즈다.
     */
    private val BLOCKQUOTE = Regex("""^\s{0,3}>\s?""")

    fun toPlainText(raw: String): String = inline(raw)
        .lineSequence()
        // **인용 표시를 가장 먼저 걷는다.** 아래 판정이 전부 줄 앞을 보므로, 여기서 안
        // 걷으면 인용 안의 표·구분선이 규칙을 통째로 비켜 간다(#191 리뷰).
        .map { it.replace(BLOCKQUOTE, "") }
        .filterNot { TABLE_DIVIDER.matches(it) } // `|---|---|` 는 읽을 내용이 아니다
        .map { line ->
            when {
                THEMATIC_BREAK.matches(line) -> ""
                // 표는 칸을 ` · ` 로 이어 한 줄로 읽는다. 좁은 화면에서 표를 그리면
                // 칸이 줄바꿈으로 뭉개져 오히려 못 읽는다.
                TABLE_ROW.matches(line) -> line.trim().trim('|').split('|')
                    .joinToString(" · ") { it.trim() }
                else -> line.replace(HEADING, "")
            }
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n") // 기호를 지우며 생긴 빈 줄을 좁힌다
        .trim()

    /**
     * 강조·링크를 **줄로 자르기 전에** 먼저 없앤다.
     *
     * 원본이 소프트 랩이라 `**…**` 가 줄을 넘어간다(`tos.md:25-26` 등). 줄 단위로 처리하면
     * 여는 `**` 와 닫는 `**` 가 서로 다른 줄에 있어 **한쪽만 남는다.**
     */
    private fun inline(text: String): String = text
        .let { BOLD.replace(it) { m -> m.groupValues[1] } }
        .let { LINK.replace(it) { m -> plainLink(m.groupValues[1], m.groupValues[2]) } }

    /** 앱에서 열 수 있는 주소만 남긴다. 저장소 상대 경로는 글자만 남긴다. */
    private fun plainLink(text: String, url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) "$text ($url)" else text
}
