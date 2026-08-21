package com.runninggu.app

import android.app.Application
import com.runninggu.app.data.ServiceLocator
import android.util.Log
import com.kakao.vectormap.KakaoMapSdk
import com.runninggu.app.ui.favorite.FavoriteStore
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
        FavoriteStore.bind(appScope)
        // 기기에서 값을 얻는 것들(위치)이 Context 를 필요로 한다
        ServiceLocator.bind(this)
        initKakaoMap()
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
        } catch (e: Throwable) {
            // **Exception 이 아니라 Throwable 로 받는다.** SDK 가 네이티브 라이브러리를 여는데,
            // 실패하면 UnsatisfiedLinkError — Exception 이 아닌 Error 라 catch(Exception) 을 빠져나가
            // 앱이 통째로 죽었다. x86_64 에뮬레이터에는 카카오맵이 그 ABI 용 .so 를 주지 않아
            // 항상 이 경로로 온다(arm64-v8a · armeabi-v7a 만 있다).
            Log.w(TAG, "카카오맵 SDK 초기화 실패 — 지도만 비활성화됩니다", e)
        }
    }

    private companion object {
        const val TAG = "RunningGuApplication"
    }
}
