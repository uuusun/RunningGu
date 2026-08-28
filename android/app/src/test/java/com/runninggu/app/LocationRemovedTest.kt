package com.runninggu.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **위치가 다시 들어오지 않는지 지킨다.** (SPEC 결정-56 · 이슈 #215)
 *
 * 기기 위치를 없앤 것은 기능 결정이 아니라 **법적 부담을 지지 않기 위한 결정**이다 —
 * 위치기반서비스사업 신고 대상이 되면 약관·보존 의무·신고가 통째로 따라온다(#195).
 * 그래서 되살아나는 것을 코드가 막아야 한다.
 *
 * ## 왜 소스를 글자로 읽나
 *
 * 지운 것은 **클래스가 아니라 존재 자체**라, 타입으로 참조할 대상이 없어 보통의 단위
 * 테스트로는 겨냥할 수 없다. 권한 선언과 의존성도 Kotlin 코드가 아니다. 그래서 파일을
 * 읽어 금지된 낱말이 있는지 본다.
 *
 * 개인정보 문안 v1.2 가 "위치 권한을 선언하지도 않습니다" 라고 **단언**하므로(#217),
 * 이 테스트가 깨지는 것은 곧 문안이 거짓이 된다는 뜻이다.
 */
class LocationRemovedTest {

    /** 유닛 테스트의 작업 디렉터리는 모듈(`android/app`)이다. 저장소 어디서 돌려도 찾도록 둘 다 본다. */
    private fun moduleFile(path: String): File =
        listOf(File(path), File("android/app/$path")).first { it.exists() }

    private val forbiddenPermissions = listOf(
        "ACCESS_FINE_LOCATION",
        "ACCESS_COARSE_LOCATION",
        "ACCESS_BACKGROUND_LOCATION",
        "FOREGROUND_SERVICE_LOCATION",
    )

    /**
     * 주석은 걷고 본다. **선언하지 않는다는 사실을 주석으로 적어 두는 것이 이 파일의 방식**이라
     * (`<!-- 위치 권한을 선언하지 않는다 -->`), 글자만 세면 그 설명 자체가 걸린다.
     */
    private fun String.withoutXmlComments(): String = replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    /** 윈도우는 `\` 로 경로를 적는다. 변형 이름을 찾으려면 한 모양으로 맞춰야 한다. */
    private val File.invariantPath: String get() = path.replace(File.separatorChar, '/')

    @Test
    fun `매니페스트에 위치 권한이 없다`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText().withoutXmlComments()

        forbiddenPermissions.forEach { permission ->
            assertTrue(
                "$permission 이 다시 선언됐다. 결정-56 은 이 넷을 선언·요청하지 않는다",
                !manifest.contains(permission),
            )
        }
    }

    @Test
    fun `위치 의존성이 없다`() {
        // `FusedLocationProvider` 는 GPS 전용이 아니다 — Wi-Fi·기지국 기반 위치도 돌려주므로
        // 라이브러리째 뺀다. 남겨 두면 "쓰지 않기로 했다" 는 규칙에만 기대게 된다
        val gradle = moduleFile("build.gradle.kts").readText()
        val versions = listOf(File("../gradle/libs.versions.toml"), File("android/gradle/libs.versions.toml"))
            .first { it.exists() }.readText()

        assertTrue("play-services-location 이 되살아났다", !gradle.contains("play.services.location"))
        assertTrue("play-services-location 이 되살아났다", !versions.contains("play-services-location"))
    }

    @Test
    fun `앱 소스에 위치 조회 API 가 없다`() {
        // 권한 없이도 부를 수 있는 것들이 섞여 있다. Wi-Fi 스캔·Cell-ID 는 결정-56 이
        // 명시적으로 막은 **간접 추정 경로**다
        val forbidden = listOf(
            "FusedLocationProvider",
            "LocationServices",
            "getLastKnownLocation",
            "requestLocationUpdates",
            "startScan(",
            "getScanResults",
            "getConnectionInfo",
            "CellInfo",
            "getAllCellInfo",
        )

        val offenders = moduleFile("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                forbidden.filter { text.contains(it) }.map { "${file.name} → $it" }
            }
            .toList()

        assertTrue("위치를 알아내는 경로가 다시 들어왔다: $offenders", offenders.isEmpty())
    }

    @Test
    fun `병합된 매니페스트에도 위치 권한이 없다`() {
        // 라이브러리가 자기 매니페스트로 권한을 밀어 넣을 수 있어서 **결과물**도 본다.
        //
        // 보는 것은 **debug 것뿐이다.** 이 테스트가 도는 변형이 debug 이고, 다른 변형의
        // 산출물은 마지막으로 빌드한 시점의 것이라 지금 소스와 무관하다 — 그걸 세면
        // 옛 빌드가 남아 있다는 이유로 빨간불이 된다.
        //
        // 빌드 산출물이라 테스트만 돌린 경우에는 아예 없다. 그때는 건너뛴다.
        val merged = listOf(File("build/intermediates/merged_manifests"), File("android/app/build/intermediates/merged_manifests"))
            .firstOrNull { it.exists() }
            ?.walkTopDown()
            ?.filter { it.isFile && it.name == "AndroidManifest.xml" && it.invariantPath.contains("/debug/") }
            ?.toList()
            .orEmpty()

        assumeTrue("병합 매니페스트가 아직 없다 — assembleDebug 뒤에만 확인된다", merged.isNotEmpty())

        merged.forEach { file ->
            val text = file.readText()
            forbiddenPermissions.forEach { permission ->
                assertTrue(
                    "${file.path} 에 $permission 이 들어왔다 — 라이브러리가 밀어 넣었을 수 있다",
                    !text.contains(permission),
                )
            }
        }
    }
}
