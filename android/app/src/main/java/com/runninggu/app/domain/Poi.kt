package com.runninggu.app.domain

/** 위경도. 내부 표준은 `lat`/`lng` 이고 변환은 경계에서만 한다. (SPEC §6.6) */
data class LatLng(val lat: Double, val lng: Double)

/**
 * 장소 하나. (SPEC §6.3 `Poi`)
 *
 * @param desc 카카오 `category_name` 말단 또는 KTO 분류. 블록 설명의 기본값으로 쓴다.
 */
data class Poi(
    val name: String,
    val lat: Double,
    val lng: Double,
    val desc: String = "",
    val addr: String = "",
    val url: String = "",
)

/**
 * POI 를 어디서 얻었는지. 결과 화면이 배지로 노출한다. (SPEC §6.3 · NFR-2)
 *
 * 원본 웹 구현은 소문자(`live`·`sample`·`synth`)를 쓰지만 §6.3 이 **Enum 대문자 통일**을
 * 요구하므로 포팅하며 맞춘다.
 */
enum class PoiSourceKind { LIVE, SAMPLE, SYNTH }

/** 카테고리 한 건의 조회 결과. (SPEC §6.3) */
data class PoiResult(
    val source: PoiSourceKind,
    val places: List<Poi>,
)

/**
 * 동선 엔진이 쓰는 POI 공급원.
 *
 * **엔진이 직접 조회하지 않는 이유** — 원본 `engine.js` 는 함수 안에서 네트워크를 부른다.
 * 그대로 옮기면 `domain` 이 네트워크에 묶여 단위 테스트가 불가능해진다. 조회를 밖으로 빼고
 * 엔진은 받은 POI 로 조립만 한다.
 *
 * 실제 구현은 `data/remote` 가 서버를 거쳐 채운다(AP-14). 앱은 카카오·KTO 를 직접 부르지 않는다.
 * 테스트에서는 가짜 구현을 끼운다.
 */
interface PoiSource {
    suspend fun load(category: PoiCategory, center: LatLng, count: Int): PoiResult
}
