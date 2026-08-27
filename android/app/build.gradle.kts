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

/** 배포 호스트가 정해지기 전까지 릴리스에 들어가는 자리표시자. */
val placeholderApiBaseUrl = "https://api.runninggu.example/api/"

/**
 * 릴리스가 볼 백엔드 주소. (AP-07 배포 · 이슈 #195)
 *
 * **배포 호스트가 정해지면 `local.properties` 에 `API_BASE_URL` 한 줄만 넣는다.** 그전에는
 * 자리표시자가 들어가는데, 그 상태로 만든 APK 는 **서버를 못 찾는다** — 화면은 전부
 * 네트워크 오류다. 스토어에 그대로 올리면 심사에서 바로 걸린다.
 *
 * 그래서 자리표시자면 빌드할 때 경고를 띄운다. 조용히 지나가면 알아채는 자리가 없다.
 *
 * **빈 값은 없는 것으로 본다.** `API_BASE_URL=` 처럼 키만 있으면 `getProperty` 가 빈 문자열을
 * 돌려준다. 그대로 두면 자리표시자 경고조차 뜨지 않은 채 `baseUrl("")` 이 되어, 앱이 켜지자마자
 * Retrofit 초기화에서 죽는 산출물이 나온다 (#196 리뷰).
 */
val apiBaseUrl: String = localProperties.getProperty("API_BASE_URL")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: placeholderApiBaseUrl

/**
 * `API_BASE_URL` 이 Retrofit 이 받는 모양인지 본다. (#196 리뷰)
 *
 * Retrofit 의 `baseUrl` 은 **`/` 로 끝나야** 한다 — 아니면 `IllegalArgumentException: baseUrl
 * must end in /` 로 앱이 시작하자마자 죽는다. 평문 http 는 릴리스에서 cleartext 차단에 걸린다.
 * 둘 다 **빌드는 성공하는데 설치본만 못 쓰는** 것이라, 이 PR 이 없애려던 바로 그 종류다.
 *
 * 자리표시자는 여기서 걸리지 않는다. 그건 잘못 적은 값이 아니라 "아직 안 정함" 이라 경고로 족하다.
 */
fun apiBaseUrlProblems(url: String): List<String> = buildList {
    if (!url.startsWith("https://")) add("https:// 로 시작해야 합니다")
    if (!url.endsWith("/")) add("Retrofit baseUrl 은 / 로 끝나야 합니다")
    if (url.any { it.isWhitespace() }) add("공백이 들어 있습니다")
    // 역슬래시는 코드값(92)으로 본다. 문자 리터럴로 적으면 이스케이프가 겹쳐 읽기 어렵다.
    if (url.any { it == '"' || it.code == 92 }) add("따옴표와 역슬래시는 넣을 수 없습니다")
}

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
val releaseStorePath: String? = localProperties.getProperty("RELEASE_STORE_FILE")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

val releaseStoreFile: File? = releaseStorePath?.let(::File)?.takeIf { it.exists() }

/** 서명에 필요한 네 줄. **다 있거나 다 없어야 한다.** */
val releaseSigningKeys = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD",
)

/** 네 줄 중 실제로 채워진 것. 빈 값은 안 적은 것으로 본다. */
val filledSigningKeys: List<String> =
    releaseSigningKeys.filterNot { localProperties.getProperty(it).isNullOrBlank() }

/**
 * 서명 설정이 쓸 수 있는 상태인가. (#196 리뷰)
 *
 * `API_BASE_URL` 과 같은 기준이다 — **안 적은 것은 경고, 적었는데 모자란 것은 실패다.**
 *
 * **네 줄을 한 덩어리로 본다.** 처음에는 `RELEASE_STORE_FILE` 부터 확인하고 없으면 그냥
 * 돌아갔는데, 그러면 **경로만 빼고 비밀번호·별칭을 적은 경우가 "아무것도 안 적음" 으로**
 * 처리돼 경고만 뜨고 빌드가 성공했다. README 는 "하나만 빠져도 멈춘다" 고 적어 두었으니
 * 문서와 구현이 어긋난 자리였다(#196 재리뷰).
 *
 * 그래서 **하나라도 적혀 있으면 나머지 셋과 keystore 파일 존재를 전부 본다.**
 * 서명 없이 만들려면 네 줄을 모두 비워야 한다 — 그때만 경고로 지나간다.
 *
 * **값은 찍지 않는다** — 비밀번호다(AGENTS 8장). 키 이름만으로 무엇을 채울지 알 수 있다.
 * 경로는 오타를 찾으려면 보여야 해서 예외다.
 */
val releaseSigningProblems: List<String> = buildList {
    // 넷 다 비었으면 "아직 안 정함" 이다. 그건 경고가 맡는다
    if (filledSigningKeys.isEmpty()) return@buildList
    releaseSigningKeys.filterNot { it in filledSigningKeys }
        .forEach { add("$it 가 비어 있습니다") }
    // 경로를 적었는데 그 자리에 파일이 없으면 오타에 가깝다. 경로는 찍어야 찾을 수 있다
    if (releaseStorePath != null && releaseStoreFile == null) {
        add("keystore 를 찾을 수 없습니다: $releaseStorePath")
    }
}

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
        // **네 줄이 다 있을 때만 만든다.** 반쯤 채워진 설정을 붙이면 AGP 가 packaging 단계에서
        // 자기 문구로 먼저 실패해, 아래 릴리스 태스크가 내놓을 진짜 이유가 묻힌다 (#196 리뷰).
        releaseStoreFile?.takeIf { releaseSigningProblems.isEmpty() }?.let { keystore ->
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
            // 따옴표·역슬래시는 이스케이프해서 넣는다. 잘못 적힌 값이 Kotlin 문법 오류로
            // 먼저 터지면, 아래 릴리스 태스크가 내놓는 진짜 이유가 묻힌다 (#196 리뷰).
            val escapedBaseUrl = apiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", "BASE_URL", "\"$escapedBaseUrl\"")
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
 * 조용히 지나가는 종류들이다 — 자리표시자 주소로 만든 APK 는 설치는 되지만 화면이 전부
 * 네트워크 오류이고, 서명 없는 APK 는 스토어가 받지 않는다. **빌드가 성공했다는 것만 보고
 * 올리면 그때 알게 된다.**
 *
 * **없는 것은 경고, 잘못 적은 것은 실패다** (#196 리뷰).
 * 값이 아예 없는 것은 아직 안 정했다는 뜻이라 막지 않는다 — CI 에는 `local.properties` 자체가
 * 없고, keystore 없이 릴리스가 컴파일되는지는 확인할 수 있어야 한다. 반면 `API_BASE_URL` 을
 * 적었는데 모양이 틀린 것은 오타이고, 그렇게 나온 산출물은 켜자마자 죽는다. 경고로 흘리면
 * 이 PR 이 없애려던 자리를 그대로 남기는 셈이라 태스크를 실패시킨다.
 */
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    // **설정 시점에 값으로 읽어 둔다.** 람다가 스크립트 속성을 그대로 붙들면 구성 캐시가
    // 직렬화하지 못한다(`cannot serialize Gradle script object references`).
    val currentBaseUrl = apiBaseUrl
    val placeholderUrl = currentBaseUrl.contains("runninggu.example")
    val baseUrlProblems = if (placeholderUrl) emptyList() else apiBaseUrlProblems(currentBaseUrl)
    val signingProblems = releaseSigningProblems
    // **넷 다 비었을 때만** 경고다. 하나라도 적혔는데 모자라면 아래에서 실패시킨다
    val unsigned = filledSigningKeys.isEmpty()
    doFirst {
        if (baseUrlProblems.isNotEmpty()) {
            throw GradleException(
                "[release] local.properties 의 API_BASE_URL 을 쓸 수 없습니다 — " +
                    baseUrlProblems.joinToString(", ") +
                    ". 지금 값: \"$currentBaseUrl\" (예: https://api.example.com/api/)",
            )
        }
        if (signingProblems.isNotEmpty()) {
            throw GradleException(
                "[release] local.properties 의 릴리스 서명 설정이 모자랍니다 — " +
                    signingProblems.joinToString(", ") +
                    ". 네 줄(RELEASE_STORE_FILE·STORE_PASSWORD·KEY_ALIAS·KEY_PASSWORD)이 " +
                    "다 있어야 서명합니다. 서명 없이 만들려면 네 줄을 모두 빼세요.",
            )
        }
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
    // A1 카카오 로그인 (AP-08 · §4.1 · §1-7). 로그인만 쓰므로 v2-user 하나면 된다
    implementation(libs.kakao.user)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // 공식 페이지를 Custom Tabs 로 연다 (SPEC §4.6 📱전환 · AP-11)
    implementation(libs.androidx.browser)
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