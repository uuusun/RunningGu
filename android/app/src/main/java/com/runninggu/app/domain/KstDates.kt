package com.runninggu.app.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 비즈니스 날짜는 전부 KST 기준이다. (SPEC §6.6)
 *
 * 기기 타임존을 그대로 쓰면 해외에서 D-day 가 하루 어긋난다. "오늘"이 필요한 곳은
 * 반드시 [today] 를 거친다.
 */
val KST: ZoneId = ZoneId.of("Asia/Seoul")

/** KST 기준 오늘. */
fun today(): LocalDate = LocalDate.now(KST)

private val DOW_KO = listOf("월", "화", "수", "목", "금", "토", "일")

/** 요일 한 글자. `2026-08-22` → `토` */
fun dowKo(date: LocalDate): String = DOW_KO[date.dayOfWeek.value - 1]

/** `MM.DD 요일`. 카드·일자 탭에 쓴다. */
fun shortKo(date: LocalDate): String =
    "%02d.%02d %s".format(date.monthValue, date.dayOfMonth, dowKo(date))

/** `MM.DD ~ MM.DD` 여행 기간 라벨. */
fun tripRangeLabel(start: LocalDate, end: LocalDate): String =
    "%02d.%02d ~ %02d.%02d".format(start.monthValue, start.dayOfMonth, end.monthValue, end.dayOfMonth)

/** b - a 일수. */
fun diffDays(a: LocalDate, b: LocalDate): Int = ChronoUnit.DAYS.between(a, b).toInt()

/**
 * 대회일 기준 오프셋 → 라벨. `-1 → D-1` · `0 → D-day` · `+2 → D+2`
 */
fun offLabel(off: Int): String = when {
    off == 0 -> "D-day"
    off < 0 -> "D$off"
    else -> "D+$off"
}

/** 대회일까지 남은 일수. 오늘이 대회일이면 0. */
fun dDay(raceDate: LocalDate, from: LocalDate = today()): Int = diffDays(from, raceDate)

/** start~end 를 양끝 포함해 하루씩. */
fun dateRange(start: LocalDate, end: LocalDate): List<LocalDate> {
    if (end.isBefore(start)) return emptyList()
    return (0..diffDays(start, end)).map { start.plusDays(it.toLong()) }
}
