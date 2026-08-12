package com.runninggu.app.ui.sample

import com.runninggu.app.ui.model.FestivalSummary
import com.runninggu.app.ui.model.RaceSummary
import java.time.LocalDate

/**
 * 화면 확인용 임시 데이터. 값은 목업 v2(docs/mockup-design)의 예시를 따랐다.
 *
 * TODO(AP-14): 백엔드 API 클라이언트가 붙으면 이 파일을 통째로 삭제한다.
 * 화면은 UiState만 보므로 각 ViewModel의 호출부만 바꾸면 된다.
 */
object SampleData {

    private val today: LocalDate = LocalDate.now()

    private fun race(
        id: String,
        name: String,
        region: String,
        venue: String,
        inDays: Long,
        startTime: String,
        regEndInDays: Long?,
        eventTypes: List<String>,
        source: String = "마라톤온라인",
        regStartInDays: Long = -30,
    ) = RaceSummary(
        id = id,
        name = name,
        region = region,
        venue = venue,
        date = today.plusDays(inDays),
        startTime = startTime,
        regStart = today.plusDays(regStartInDays),
        regEnd = regEndInDays?.let { today.plusDays(it) },
        eventTypes = eventTypes,
        source = source,
        checked = today.minusDays(1),
    )

    val races: List<RaceSummary> = listOf(
        race("seoul-hangang", "서울 한강 러닝 페스티벌", "서울", "여의도한강공원", 18, "09:00", 4, listOf("10K", "5K")),
        race("sejong-lake", "세종 호수공원 마라톤", "세종", "세종 호수공원", 21, "08:00", 9, listOf("하프", "10K", "5K")),
        race("incheon-bridge", "인천 송도 브릿지런", "인천", "송도 센트럴파크", 26, "08:30", -3, listOf("하프", "10K")),
        race("busan-sea", "부산 바다마라톤", "부산", "광안리해수욕장", 40, "08:30", 27, listOf("풀", "하프", "10K")),
        race("daegu-color", "대구 컬러런", "대구", "두류공원", 45, "10:00", 30, listOf("5K")),
        race("gangwon-trail", "강원 대관령 트레일런", "강원", "대관령 양떼목장", 52, "07:00", 38, listOf("하프", "10K"), source = "로드런"),
        race("gwangju-flower", "광주 꽃길 마라톤", "광주", "5·18 기념공원", 58, "09:00", 44, listOf("10K", "5K")),
        race("jeju-olle", "제주 올레 트레일런", "제주", "성산일출봉 일원", 75, "07:30", 62, listOf("하프", "10K"), source = "로드런"),
        race("gyeonggi-lake", "경기 호수공원 하프", "경기", "일산 호수공원", 82, "08:00", 68, listOf("하프", "10K", "5K")),
        race("daejeon-science", "대전 사이언스런", "대전", "엑스포시민광장", 90, "09:30", 76, listOf("10K", "5K")),
        race("ulsan-whale", "울산 고래축제 마라톤", "울산", "태화강국가정원", 96, "08:00", 82, listOf("풀", "하프", "10K")),
        race("jeonnam-green", "전남 순천만 갈대런", "전남", "순천만습지", 104, "08:30", null, listOf("하프", "10K"), regStartInDays = 20),
    )

    val festivals: List<FestivalSummary> = listOf(
        FestivalSummary("fest-busan", "부산 바다축제", "부산", "08.01~08.09", isOngoing = true),
        FestivalSummary("fest-bonghwa", "봉화 은어축제", "경북", "08.01~08.10", isOngoing = true),
        FestivalSummary("fest-sejong", "세종 호수공원 물빛축제", "세종", "08.20~08.24", isOngoing = false),
        FestivalSummary("fest-pyeongchang", "평창 백일홍축제", "강원", "08.28~09.13", isOngoing = false),
    )
}
