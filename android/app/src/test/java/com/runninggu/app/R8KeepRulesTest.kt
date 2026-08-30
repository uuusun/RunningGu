package com.runninggu.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **R8 을 도로 끄거나 keep rule 을 지우지 못하게 막는다.** (이슈 #232 · NFR-14)
 *
 * ## 왜 이 테스트가 필요한가
 *
 * R8 이 깨뜨리는 것은 **빌드가 아니라 런타임**이다. 규칙을 지워도 `assembleRelease` 는
 * 그대로 성공하고, APK 도 나오고, 앱도 뜬다. 죽는 것은 특정 화면 하나다 — 그것도
 * **릴리스 빌드에서만**. CI 는 debug 만 돌리므로(#83 결정) 아무 신호도 없다.
 *
 * 그래서 "규칙이 있는지" 를 글자로 확인한다. 약한 검사인 것은 맞지만, 이 자리에서
 * 실제로 일어날 사고는 **누가 규칙을 정리하다 지우는 것**이지 규칙이 미묘하게 틀리는
 * 것이 아니다. 지우는 것은 이걸로 잡힌다.
 *
 * ## 규칙 없이 켜면 실제로 이렇게 됐다 (#232 실측)
 *
 * ```
 * VerifyCodeResponseDto            APK 에서 통째로 사라짐 — verifySignupCode 는 남음
 * com.kakao.vectormap.*            난독화됨 — 네이티브가 이름으로 찾는 클래스들
 * KakaoMapReadyCallback            R8$$REMOVED$$CLASS
 * ```
 *
 * 앞의 것은 이메일 인증 코드 확인이, 뒤의 것은 지도가 릴리스에서만 죽는다.
 */
class R8KeepRulesTest {

    /** 유닛 테스트의 작업 디렉터리는 모듈(`android/app`)이다. 저장소 어디서 돌려도 찾도록 둘 다 본다. */
    private fun moduleFile(path: String): File =
        listOf(File(path), File("android/app/$path")).first { it.exists() }

    private val buildScript by lazy { moduleFile("build.gradle.kts").readText() }
    private val keepRules by lazy { moduleFile("src/main/keepRules/runninggu.keep").readText() }

    @Test
    fun `릴리스 빌드는 R8 을 켠다`() {
        assertTrue(
            "release 에 isMinifyEnabled = true 가 없다. NFR-14 는 릴리스 R8 활성을 확정해 뒀다(#232).",
            buildScript.contains("isMinifyEnabled = true"),
        )
        assertTrue(
            "release 에서 R8 을 다시 껐다(optimization { enable = false }). NFR-14 위반이다(#232).",
            !buildScript.contains("enable = false"),
        )
    }

    /**
     * DTO 는 우리 코드가 만들지 않는다 — Retrofit 이 시그니처를 리플렉션으로 읽어 만든다.
     * R8 에게는 아무도 안 쓰는 클래스로 보여서, 규칙이 없으면 통째로 지워진다.
     */
    @Test
    fun `직렬화 DTO 를 남기는 규칙이 있다`() {
        assertTrue(
            "@Serializable DTO keep rule 이 없다. 지우면 릴리스에서 응답 파싱이 죽는다(#232).",
            keepRules.contains("@kotlinx.serialization.Serializable class com.runninggu.app.data."),
        )
        assertTrue(
            "생성된 serializer keep rule 이 없다. 클래스가 남아도 직렬화가 SerializationException 으로 떨어진다.",
            keepRules.contains("\$\$serializer"),
        )
    }

    /**
     * 지도 SDK 는 AAR 에 `proguard.txt` 가 없어 **자기 자신을 못 지킨다.** 그런데 네이티브
     * (`libK3fAndroid.so`)가 `com/kakao/vectormap/LatLng` 같은 경로를 문자열로 들고 있다.
     * 이름이 바뀌면 `FindClass` 가 실패해 **지도만 안 그려진다.**
     */
    @Test
    fun `카카오맵을 남기는 규칙이 있다`() {
        assertTrue(
            "com.kakao.vectormap keep rule 이 없다. 난독화되면 네이티브가 클래스를 못 찾아 지도가 죽는다(#232).",
            keepRules.contains("-keep class com.kakao.vectormap.** { *; }"),
        )
    }
}
