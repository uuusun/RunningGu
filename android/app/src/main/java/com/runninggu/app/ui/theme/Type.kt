package com.runninggu.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.runninggu.app.R

/** 가변 폰트 하나에서 두께별 Font를 만든다. minSdk 26이라 가변 폰트를 쓸 수 있다. */
@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private val WEIGHTS = listOf(
    FontWeight.Normal,   // 400 — 본문·메타
    FontWeight.Medium,   // 500
    FontWeight.SemiBold, // 600 — 카드 제목
    FontWeight.Bold,     // 700
    FontWeight.ExtraBold, // 800 — 화면 타이틀·섹션 헤딩·숫자
)

/** 본문 — Pretendard (목업 --font-body). */
val Pretendard = FontFamily(WEIGHTS.map { variableFont(R.font.pretendard_variable, it) })

/**
 * 숫자·영문 라벨 — Archivo (목업 --font-num).
 * D-day·거리·날짜 컬럼(AUG / 31)처럼 수치를 강조하는 자리에만 쓴다.
 */
val Archivo = FontFamily(WEIGHTS.map { variableFont(R.font.archivo_variable, it) })

/**
 * 타이포 스케일. 목업 v2 '디자인 시스템' 화면의 표기를 그대로 옮겼다.
 *
 * | 목업 표기        | 크기/두께  | Material 슬롯   |
 * |----------------|-----------|----------------|
 * | 화면 타이틀      | 26 / 800  | headlineSmall  |
 * | 섹션 헤딩        | 17 / 800  | titleMedium    |
 * | 카드 제목        | 15 / 600  | titleSmall     |
 * | 본문·보조 설명    | 13.5 / 400| bodyMedium     |
 * | 메타·출처·확인일  | 12 / 400  | bodySmall      |
 */
val Typography = Typography(
    headlineSmall = TextStyle(
        fontFamily = Pretendard,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Pretendard,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Pretendard,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Pretendard,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.3).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Pretendard,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Pretendard,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Pretendard,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Pretendard,
        fontWeight = FontWeight.Bold,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Pretendard,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Pretendard,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

/** 숫자 강조용 스타일 — 날짜 컬럼의 일(日), D-day, 거리 등. (목업 --font-num) */
val NumeralLarge = TextStyle(
    fontFamily = Archivo,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 28.sp,
    lineHeight = 29.sp,
    letterSpacing = (-0.6).sp,
)

/** 날짜 컬럼의 월(AUG)처럼 작고 자간 넓은 영문 라벨. */
val NumeralLabel = TextStyle(
    fontFamily = Archivo,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 11.5.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.7.sp,
)
