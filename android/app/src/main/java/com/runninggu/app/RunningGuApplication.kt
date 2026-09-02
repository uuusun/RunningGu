package com.runninggu.app

import android.app.Application
import android.util.Log
import com.kakao.sdk.common.KakaoSdk
import com.kakao.vectormap.KakaoMapSdk
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.local.DataStoreSessionPersistence
import com.runninggu.app.ui.auth.KakaoAuthAvailability
import com.runninggu.app.data.local.SessionStore
import com.runninggu.app.ui.favorite.FavoriteStore
import com.runninggu.app.ui.apiFailureLogger
import com.runninggu.app.ui.map.MapAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 앱 수명과 함께 살아야 하는 것들을 여기서 시작한다.
 *
 * 화면 ViewModel 의 `viewModelScope` 에 매어 두면 화면이 사라질 때 함께 죽는다. 로그인
 * 직후 원래 자리로 복귀(D-27)하면서 찜 조회가 취소되던 것이 그 경우다(#64 리뷰).
 */
class RunningGuApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // 저장 실패의 개발자용 신원(status·code·traceId)을 logcat 으로 흘린다 (이슈 #252).
        // 화면에는 서버가 준 `title` 만 뜨므로 `code` 는 여기서만 보인다.
        // 단위 테스트는 Application 을 안 띄워 기본 no-op 그대로다
        apiFailureLogger = { Log.w(TAG, it) }
        FavoriteStore.bind(appScope)
        // 저장된 세션을 올린다. 시작 화면이 SessionStore.restored 를 기다린다 (SPEC §2.2)
        SessionStore.bind(
            persistence = DataStoreSessionPersistence(this),
            scope = appScope,
            // A0 — 디스크에 남은 토큰이 아직 쓸 수 있는지 서버에 물어본다
            validator = ServiceLocator.sessionValidator,
        )
        initKakaoMap()
        initKakaoAuth()
    }

    /**
     * 카카오 로그인 SDK 초기화. (SPEC §4.1 · §1-7 · AP-08)
     *
     * 지도와 **같은 네이티브 앱 키**를 쓰지만 초기화는 따로다(다른 SDK 다). 지도가 실패해도
     * 로그인은 될 수 있고 그 반대도 마찬가지라, 한쪽 실패가 다른 쪽을 끌고 가지 않게 나눈다.
     *
     * **실패해도 앱을 죽이지 않는다.** 키가 없으면(CI·키 못 받은 팀원) 카카오 버튼만 막히고
     * 이메일 로그인은 그대로 쓴다(NFR-1·3). 그 판정은 [KakaoAuthAvailability] 가 들고 있다.
     *
     * 키 값은 로그에 남기지 않는다(AGENTS 8장).
     */
    private fun initKakaoAuth() {
        val appKey = BuildConfig.KAKAO_NATIVE_APP_KEY
        if (appKey.isBlank()) {
            Log.w(TAG, "카카오 네이티브 앱 키가 없어 카카오 로그인을 끕니다. local.properties 를 확인하세요")
            return
        }
        try {
            KakaoSdk.init(this, appKey)
            KakaoAuthAvailability.markReady()
        } catch (e: Exception) {
            // 지도와 달리 네이티브 라이브러리를 열지 않아 LinkageError 를 따로 받지 않는다
            Log.w(TAG, "카카오 로그인 SDK 초기화 실패 — 카카오 로그인만 비활성화됩니다", e)
        }
    }

    /**
     * 카카오맵 SDK 초기화. (SPEC §3-8 · AP-03)
     *
     * **실패해도 앱을 죽이지 않는다.** 키가 없거나(CI·키 못 받은 팀원) 초기화가 실패하면
     * 지도 화면만 안내 문구로 떨어지고 나머지는 그대로 쓴다(NFR-1·3).
     *
     * 키 값은 로그에 남기지 않는다(AGENTS 8장).
     */
    private fun initKakaoMap() {
        val appKey = BuildConfig.KAKAO_NATIVE_APP_KEY
        if (appKey.isBlank()) {
            Log.w(TAG, "카카오 네이티브 앱 키가 없어 지도를 끕니다. local.properties 를 확인하세요")
            return
        }
        try {
            KakaoMapSdk.init(this, appKey)
            // 여기까지 와야 지도를 그린다 — MapView 는 초기화 실패를 안 알려 준다(#162)
            MapAvailability.markReady()
        } catch (e: LinkageError) {
            // **Exception 과 따로 받는다.** SDK 가 네이티브 라이브러리를 여는데, 실패하면
            // UnsatisfiedLinkError — Exception 이 아닌 Error 라 catch(Exception) 을 빠져나가
            // 앱이 통째로 죽었다. x86_64 에뮬레이터에는 카카오맵이 그 ABI 용 .so 를 주지 않아
            // 항상 이 경로로 온다(arm64-v8a · armeabi-v7a 만 있다).
            //
            // **Throwable 로 넓히지 않는다**(#110 리뷰). OutOfMemoryError 처럼 복구할 수 없는
            // 것까지 "지도만 끕니다" 로 삼키면 앱이 망가진 채로 계속 돈다. LinkageError 는
            // 클래스·네이티브 적재 실패만 묶으므로 여기서 감당할 수 있는 범위와 같다.
            Log.w(TAG, "카카오맵 네이티브 라이브러리를 열지 못해 지도를 끕니다", e)
        } catch (e: Exception) {
            Log.w(TAG, "카카오맵 SDK 초기화 실패 — 지도만 비활성화됩니다", e)
        }
    }

    private companion object {
        const val TAG = "RunningGuApplication"
    }
}
