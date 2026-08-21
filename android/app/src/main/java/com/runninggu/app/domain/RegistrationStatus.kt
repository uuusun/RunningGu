package com.runninggu.app.domain

import java.time.LocalDate

/**
 * 대회 접수 상태. (SPEC §5.5 · 부록 C `RegStatus`)
 *
 * 서버 DTO 의 `OPEN|BEFORE|CLOSED|UNKNOWN` 과 이름이 같다. 매핑은 `data/remote` 가 한다.
 */
enum class RegistrationStatus(val label: String) {
    BEFORE("접수전"),
    OPEN("접수중"),
    CLOSED("마감"),
    UNKNOWN("미정"),
}

/**
 * 접수 상태를 **오늘(KST) 기준으로 다시 계산한다.** (SPEC §5.5)
 *
 * 크롤 스냅샷과 캐시된 응답은 stale 하다 — 수집 시점에 "접수중" 이었어도 지금은 마감일 수 있다.
 * 그래서 날짜로 **단정할 수 있는 것만** 다시 계산하고, 나머지는 [fallback] 에 맡긴다.
 *
 * | 아는 것 | 판정 |
 * |---|---|
 * | 마감일이 지났다 | 마감 |
 * | 시작일이 아직 안 됐다 | 접수전 |
 * | 시작일을 지났다 | 접수중 |
 * | **마감일만 알고 아직 안 지났다** | **판단 불가 → [fallback]** |
 * | 날짜가 없다 | [fallback] |
 *
 * 네 번째 줄이 중요하다. 마감일이 미래라는 것만으로는 접수중이라고 할 수 없다 —
 * 아직 접수 시작 전일 수 있기 때문이다. **번들(`assets/races.json`)은 원천에 접수 시작일이
 * 없으면 빈 값으로 두므로** 실제로 이 상황이 생긴다. 이때는 원천이 적어 둔 상태나 서버가
 * 조회 시점에 파생해 준 값을 그대로 믿는 게 맞다.
 *
 * > 서버 목록 응답(§3-1)에는 `applyStart` 가 있다. 예전 주석은 없다고 적어 뒀는데
 * > PR #84 에서 들어왔다.
 *
 * ## 서버에도 같은 규칙이 있다 — **한쪽만 고치지 말 것**
 *
 * 백엔드 `ContestRegistrationStatusPolicy` 가 줄 단위로 같은 판정을 한다(SPEC §5.5).
 * **의도적인 중복이다.** 서버는 응답을 만들 때 조회일 기준으로 파생하고, 앱은 Room 읽기
 * 캐시를 며칠 뒤에 열 수 있어서 **화면에 그릴 때 다시** 계산해야 한다(§4.13 오프라인).
 * 없앨 수 있는 중복이 아니다.
 *
 * 대신 갈라지면 **서버는 "접수중" 이라 하고 앱은 "마감" 으로 그리는** 형태로 나타나서
 * 눈에 띄기까지 오래 걸린다. §5.4 종목 표준화가 두 벌로 갈라져 있던 것(이슈 #61)이 같은
 * 사고였다. 경계 조건을 바꿀 일이 생기면 양쪽을 함께 본다.
 *
 * @param fallback 서버가 내려준 값이나 원본 스냅샷의 상태. 없으면 [RegistrationStatus.UNKNOWN].
 */
fun regStatusOf(
    regStart: LocalDate?,
    regEnd: LocalDate?,
    fallback: RegistrationStatus? = null,
    today: LocalDate = today(),
): RegistrationStatus = when {
    regEnd != null && regEnd.isBefore(today) -> RegistrationStatus.CLOSED
    regStart != null && today.isBefore(regStart) -> RegistrationStatus.BEFORE
    regStart != null -> RegistrationStatus.OPEN
    else -> fallback ?: RegistrationStatus.UNKNOWN
}
