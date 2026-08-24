package com.runninggu.app.data.model

/**
 * 저장한 동선 카드 한 장. (SPEC §4.13 [동선] · API 명세 §5-4)
 *
 * **목록 응답의 부분집합이다.** `days`·`blocks` 는 상세(§5-5)에서만 온다 — 카드 하나를
 * 그리려고 트리 전체를 받지 않는다.
 *
 * `ui/my` 가 아니라 여기 있는 이유는 `data/repository` 가 이 타입을 돌려주기 때문이다.
 * 화면 패키지에 두면 **`data` 가 `ui` 를 import** 해야 하는데 그런 선례가 없다
 * (AGENTS 2장 · #173 에서 찜 저장소도 같은 이유로 옮겼다).
 */
data class SavedItinerary(
    /** 상세·삭제에 쓰는 canonical id. */
    val id: String,
    /** "{지역} {당일치기|n박 n일}" */
    val title: String,
    val raceName: String,
    val event: String,
    /** 회복 배지 라벨. noHard 종목이 아니면 null. */
    val recoveryLabel: String?,
    /** "MM.DD~MM.DD" */
    val period: String,
    val placeCount: Int,
    /**
     * 저장 snapshot 과 현재 canonical 대회가 다른가. (§5-4)
     *
     * 참이면 화면이 "대회 변경" 배지를 붙인다. **이름만 바뀌거나 [active] 만 달라진
     * 것으로는 참이 되지 않는다** — 그때까지 다시 만들라고 하면 헛걸음이다.
     */
    val needsRegeneration: Boolean = false,
    /**
     * 대회가 아직 서비스되는가. (§5-4)
     *
     * **거짓이어도 목록에서 지우지 않는다.** 사용자가 저장한 것이 말없이 사라지면
     * 안 된다 — 흐리게 보여 주고 왜 그런지 알린다.
     */
    val active: Boolean = true,
)
