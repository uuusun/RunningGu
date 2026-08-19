package com.runninggu.app.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * 서버 JSON 규약. (API 명세 §0-1 · NFR-17)
 *
 * - `ignoreUnknownKeys` — 서버가 필드를 **추가**해도 앱이 안 깨진다. 계약상 추가는 호환 변경이다
 * - `explicitNulls = false` — 명세가 "다른 종류의 항목에서 null 로 채우지 않고 생략한다" 고 못박았다
 * - `coerceInputValues` 는 쓰지 않는다 — 모르는 enum 을 조용히 기본값으로 바꾸면 계약 위반을 못 잡는다
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
    serializersModule = SerializersModule {
        contextual(LocalDate::class, LocalDateSerializer)
        contextual(LocalTime::class, LocalTimeSerializer)
        contextual(Instant::class, InstantSerializer)
    }
}

/**
 * 비즈니스 날짜 `YYYY-MM-DD`. (§0-1)
 *
 * **타임존을 붙이지 않는다.** 이 값은 이미 KST 기준으로 정해진 날짜이고,
 * "오늘" 과 비교할 때만 [com.runninggu.app.domain.today] 를 쓴다.
 */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalDate {
        val raw = decoder.decodeString()
        return try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("날짜 형식이 YYYY-MM-DD 가 아니다: $raw", e)
        }
    }
}

/** 시작 시각 `HH:mm`. (API 명세 §3 대회 응답) */
object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalTime {
        val raw = decoder.decodeString()
        return try {
            LocalTime.parse(raw)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("시각 형식이 HH:mm 이 아니다: $raw", e)
        }
    }
}

/**
 * timestamp — ISO-8601 UTC `Z`. (§0-1)
 *
 * 서버는 `timestamptz` 로 저장하고 JSON 에는 항상 `Z` 로 내려준다. 화면에 보일 때만
 * KST 로 바꾼다 — 여기서 지역 시간으로 바꾸면 저장·비교가 어긋난다.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        return try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("timestamp 가 ISO-8601 Z 형식이 아니다: $raw", e)
        }
    }
}
