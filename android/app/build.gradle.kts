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
val kakaoNativeAppKey: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("KAKAO_NATIVE_APP_KEY").orEmpty()

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

    buildTypes {
        debug {
            // 에뮬레이터에서 호스트의 localhost 는 10.0.2.2 다. 실기기 테스트는 각자 로컬 IP 로 바꾼다.
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8080/api/\"")
        }
        release {
            // TODO(AP-07 배포): 배포 호스트가 정해지면 채운다.
            buildConfigField("String", "BASE_URL", "\"https://api.runninggu.example/api/\"")
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

    sourceSets {
        // 단위 테스트가 **실제 번들**(assets/races.json)을 읽게 한다. 복사본을 두면
        // 스크립트가 만드는 모양이 바뀌어도 테스트가 안 깨져 드리프트를 놓친다.
        getByName("test") {
            resources.srcDir("src/main/assets")
        }
    }
}

dependencies {
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