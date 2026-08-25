import java.util.Properties

/**
 * 카카오 **네이티브** 앱 키. (SPEC §7.4-10 · AGENTS 8장)
 *
 * `local.properties` 는 gitignore 대상이라 저장소에 키가 남지 않는다. 각자 자기 파일에
 * `KAKAO_NATIVE_APP_KEY=...` 한 줄을 넣는다.
 *
 * **없어도 빌드는 된다.** CI 에는 키가 없고 단위 테스트는 지도를 띄우지 않기 때문이다 —
 * 여기서 빌드를 깨면 키 없는 사람이 테스트조차 못 돌린다. 대신 비어 있으면 지도 초기화에서
 * 알아채고 화면이 오류로 떨어진다(AP-03).
 *
 * REST 키는 여기 두지 않는다. 서버에만 있다(AGENTS 8장).
 */
val localProperties: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val kakaoNativeAppKey: String = localProperties.getProperty("KAKAO_NATIVE_APP_KEY").orEmpty()

/**
 * 릴리스가 볼 백엔드 주소. (AP-07 배포 · 이슈 #195)
 *
 * **배포 호스트가 정해지면 `local.properties` 에 `API_BASE_URL` 한 줄만 넣는다.** 그전에는
 * 아래 자리표시자가 들어가는데, 그 상태로 만든 APK 는 **서버를 못 찾는다** — 화면은 전부
 * 네트워크 오류다. 스토어에 그대로 올리면 심사에서 바로 걸린다.
 *
 * 그래서 자리표시자면 빌드할 때 경고를 띄운다. 조용히 지나가면 알아채는 자리가 없다.
 */
val apiBaseUrl: String = localProperties.getProperty("API_BASE_URL")
    ?: "https://api.runninggu.example/api/"

/**
 * 릴리스 서명. (AGENTS 8장 · 이슈 #108)
 *
 * **keystore 와 비밀번호는 저장소에 두지 않는다.** 각자 `local.properties` 에 네 줄을 넣고,
 * 파일은 저장소 밖에 둔다.
 *
 * ```
 * RELEASE_STORE_FILE=C:/keys/runninggu.jks
 * RELEASE_STORE_PASSWORD=...
 * RELEASE_KEY_ALIAS=runninggu
 * RELEASE_KEY_PASSWORD=...
 * ```
 *
 * **없어도 빌드는 된다** — 카카오 키와 같은 규칙이다. CI 에는 keystore 가 없고, 서명 없는
 * APK 로도 빌드가 깨지는지는 확인할 수 있어야 한다. 대신 스토어에 올릴 산출물은 아니다.
 */
val releaseStoreFile: File? = localProperties.getProperty("RELEASE_STORE_FILE")
    ?.let(::File)
    ?.takeIf { it.exists() }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.runninggu.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.runninggu.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"$kakaoNativeAppKey\"")
        // 카카오 로그인이 돌아올 때 쓰는 `kakao{네이티브키}://oauth` 스킴. (AP-08 · §4.1)
        manifestPlaceholders["kakaoNativeAppKey"] = kakaoNativeAppKey
    }

    signingConfigs {
        // `local.properties` 에 keystore 가 있을 때만 만든다.
        releaseStoreFile?.let { keystore ->
            create("release") {
                storeFile = keystore
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 에뮬레이터에서 호스트의 localhost 는 10.0.2.2 다. 실기기 테스트는 각자 로컬 IP 로 바꾼다.
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8080/api/\"")
        }
        release {
            buildConfigField("String", "BASE_URL", "\"$apiBaseUrl\"")
            // keystore 가 없으면 서명 없이 만든다 — 빌드가 깨지지 않아야 CI 가 돈다.
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // `docs/agreements/README.md` 는 **팀 내부 문서**다(미결 목록·논의 링크). 문안 파일과
        // 같은 폴더에 있어 그대로 두면 APK 에 실려 나간다 — 앱을 뜯으면 읽힌다.
        ignoreAssetsPatterns += "README.md"
    }

    sourceSets {
        getByName("main") {
            // 약관 문안을 **원본 그대로** 번들한다. (이슈 #111 · D-32)
            //
            // 복사해 두지 않는 이유는 위 `races.json` 과 같다 — 사본은 반드시 드리프트한다.
            // 여기서는 그 대가가 특히 크다. 앱이 보여준 글과 서버가 저장한 버전이 어긋나면
            // **동의 이력이 무엇에 대한 동의인지 알 수 없다**(NFR-12).
            //
            // `docs/agreements/v1.0/tos.md` → assets 의 `v1.0/tos.md` 로 들어간다.
            assets.srcDir(rootProject.file("../docs/agreements"))
        }
        // 단위 테스트가 **실제 번들**(assets/races.json)을 읽게 한다. 복사본을 두면
        // 스크립트가 만드는 모양이 바뀌어도 테스트가 안 깨져 드리프트를 놓친다.
        getByName("test") {
            resources.srcDir("src/main/assets")
            // 약관도 같은 이유로 실제 파일을 읽게 한다.
            resources.srcDir(rootProject.file("../docs/agreements"))
        }
    }
}

/**
 * 스토어에 못 올릴 산출물을 **만들 때 알려 준다.** (AP-07 배포 · 이슈 #195)
 *
 * 둘 다 조용히 지나가는 종류다 — 자리표시자 주소로 만든 APK 는 설치는 되지만 화면이 전부
 * 네트워크 오류이고, 서명 없는 APK 는 스토어가 받지 않는다. **빌드가 성공했다는 것만 보고
 * 올리면 그때 알게 된다.**
 *
 * 막지는 않는다 — CI 는 keystore 없이 릴리스가 컴파일되는지 확인해야 한다.
 */
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    // **설정 시점에 값으로 읽어 둔다.** 람다가 스크립트 속성을 그대로 붙들면 구성 캐시가
    // 직렬화하지 못한다(`cannot serialize Gradle script object references`).
    val placeholderUrl = apiBaseUrl.contains("runninggu.example")
    val unsigned = releaseStoreFile == null
    doFirst {
        if (placeholderUrl) {
            logger.warn(
                "[release] BASE_URL 이 자리표시자입니다 — local.properties 에 API_BASE_URL 을 " +
                    "넣으세요. 이대로 만든 산출물은 서버를 못 찾습니다.",
            )
        }
        if (unsigned) {
            logger.warn(
                "[release] 서명 keystore 가 없습니다 — 서명 없는 산출물이 나옵니다. 스토어 " +
                    "업로드용이면 local.properties 에 RELEASE_STORE_FILE 등을 넣으세요.",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.play.services.location)
    implementation(libs.kakao.maps)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // 네트워크 — Retrofit + OkHttp, 직렬화는 kotlinx.serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging.interceptor)

    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}