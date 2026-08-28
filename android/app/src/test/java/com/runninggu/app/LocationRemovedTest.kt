package com.runninggu.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * ## 무엇을 막는지가 "GPS" 보다 넓다
 *
 * 결정-56 은 **위치를 짐작할 수 있는 경로 전부**를 막는다 — Wi-Fi AP(SSID/BSSID·스캔
 * 결과) · 기지국 Cell-ID · BLE · IP 지역 변환이 모두 해당한다. 그래서 금지 목록도
 * `FusedLocationProvider` 하나가 아니라 **그 경로들의 타입 이름**으로 잡는다.
 *
 * 메서드 이름만 세면 뚫린다 — `wifiManager.scanResults` 는 코틀린 프로퍼티라
 * `getScanResults` 도 `startScan(` 도 포함하지 않는다(#222 리뷰). 타입을 막으면
 * 프로퍼티든 메서드든 그 타입을 쓰는 순간 걸린다.
 *
 * 개인정보 문안 v1.2 가 "위치 권한을 선언하지도 않습니다" 라고 **단언**하므로(#217),
 * 이 테스트가 깨지는 것은 곧 문안이 거짓이 된다는 뜻이다.
 */
class LocationRemovedTest {

    /** 유닛 테스트의 작업 디렉터리는 모듈(`android/app`)이다. 저장소 어디서 돌려도 찾도록 둘 다 본다. */
    private fun moduleFile(path: String): File =
        listOf(File(path), File("android/app/$path")).first { it.exists() }

    /**
     * 선언해서는 안 되는 권한. (결정-56)
     *
     * 위치 넷에 더해 **`NEARBY_WIFI_DEVICES` · `BLUETOOTH_SCAN`** 을 함께 막는다.
     * 이 둘은 이름에 `LOCATION` 이 없지만 주변 기기로 위치를 짐작하는 길이라,
     * 결정-56 이 막은 간접 추정 경로가 요구하는 권한이다(#222 리뷰).
     */
    private val forbiddenPermissions = listOf(
        "ACCESS_FINE_LOCATION",
        "ACCESS_COARSE_LOCATION",
        "ACCESS_BACKGROUND_LOCATION",
        "FOREGROUND_SERVICE_LOCATION",
        "NEARBY_WIFI_DEVICES",
        "BLUETOOTH_SCAN",
    )

    /**
     * 매니페스트에서 **선언된 권한 이름만** 뽑는다.
     *
     * 글자를 그냥 세면 안 된다. 이 저장소는 *"선언하지 않는다"* 는 사실을 주석으로 남기는
     * 방식이라(`<!-- FOREGROUND_SERVICE_LOCATION 을 선언하지 않는다 -->`), 그 설명 자체가
     * 걸린다. **release 병합 매니페스트에는 소스 주석이 그대로 실려서** 더 그렇다(#222 리뷰).
     */
    private fun declaredPermissions(manifest: String): List<String> =
        Regex("""<uses-permission[^>]*android:name\s*=\s*"([^"]+)"""")
            .findAll(manifest.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), ""))
            .map { it.groupValues[1] }
            .toList()

    private fun assertNoLocationPermission(source: String, manifest: String) {
        val offenders = declaredPermissions(manifest)
            .filter { name -> forbiddenPermissions.any { name.endsWith(it) } }

        assertEquals("$source 에 위치 계열 권한이 선언됐다", emptyList<String>(), offenders)
    }

    @Test
    fun `매니페스트에 위치 권한이 없다`() {
        assertNoLocationPermission(
            source = "소스 매니페스트",
            manifest = moduleFile("src/main/AndroidManifest.xml").readText(),
        )
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
    fun `앱 소스에 위치를 알아내는 경로가 없다`() {
        // **타입 이름으로 막는다.** 메서드만 세면 코틀린 프로퍼티 경로가 빠져나간다
        // (`wifiManager.scanResults` 는 getScanResults 를 포함하지 않는다 · #222 리뷰).
        //
        // 아래 타입은 이 앱에 정당한 쓰임이 없다. 하나라도 등장하면 위치를 짐작하는
        // 경로가 생겼다는 뜻이다.
        val forbiddenTypes = listOf(
            // 위치 직접 조회
            "FusedLocationProvider", "LocationServices", "LocationManager",
            "android.location.", "LocationRequest", "LocationCallback",
            // Wi-Fi — SSID·BSSID·스캔 결과
            "WifiManager", "WifiInfo", "ScanResult",
            // 기지국
            "TelephonyManager", "CellInfo", "CellIdentity",
            // BLE
            "BluetoothLeScanner", "BluetoothAdapter", "ScanCallback",
        )
        // 타입 없이도 부를 수 있는 것들. 위 목록과 겹쳐도 둔다 — 둘 중 하나만 남아도 잡힌다
        val forbiddenCalls = listOf(
            "getLastKnownLocation", "getCurrentLocation", "requestLocationUpdates",
            "getAllCellInfo", "cellLocation", "startScan(", "scanResults",
            "getConnectionInfo", "connectionInfo", "startLeScan",
        )

        val offenders = moduleFile("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                (forbiddenTypes + forbiddenCalls)
                    .filter { text.contains(it) }
                    .map { "${file.name} → $it" }
            }
            .toList()

        assertEquals("위치를 알아내는 경로가 다시 들어왔다", emptyList<String>(), offenders)
    }

    /**
     * **병합된 결과물**도 본다. 라이브러리가 자기 매니페스트로 권한을 밀어 넣을 수 있다.
     *
     * `debug` 와 `release` **둘 다** 확인한다 — #215 의 완료 조건이 둘이고, `release` 에만
     * 붙는 의존성이 권한을 주입해도 `debug` 만 보면 CI 가 계속 통과한다(#222 리뷰).
     *
     * 두 파일은 `app/build.gradle.kts` 가 이 테스트 태스크에 `processDebugManifest` ·
     * `processReleaseManifest` 를 물려 두어 항상 만들어져 있다. **없으면 건너뛰지 않고
     * 실패한다** — 건너뛰는 안전망은 안전망이 아니다.
     */
    @Test
    fun `병합된 매니페스트 둘 다 위치 권한이 없다`() {
        val root = listOf(File("build/intermediates/merged_manifests"), File("android/app/build/intermediates/merged_manifests"))
            .firstOrNull { it.exists() }

        val merged = root?.walkTopDown()
            ?.filter { it.isFile && it.name == "AndroidManifest.xml" }
            ?.associateBy { file -> file.invariantPath.substringAfter("merged_manifests/").substringBefore('/') }
            .orEmpty()

        listOf("debug", "release").forEach { variant ->
            val file = merged[variant]
            assertTrue(
                "$variant 병합 매니페스트가 없다. Gradle 로 돌려야 한다 — " +
                    "app/build.gradle.kts 가 process${variant.replaceFirstChar { it.uppercase() }}Manifest 를 물려 둔다",
                file != null,
            )
            assertNoLocationPermission(
                source = "$variant 병합 매니페스트",
                manifest = file!!.readText(),
            )
        }
    }

    /** 윈도우는 `\` 로 경로를 적는다. 변형 이름을 찾으려면 한 모양으로 맞춰야 한다. */
    private val File.invariantPath: String get() = path.replace(File.separatorChar, '/')
}
