package com.runninggu.app

import android.app.Application
import com.runninggu.app.data.ServiceLocator
import com.runninggu.app.data.local.DataStoreSessionPersistence
import com.runninggu.app.data.local.SessionStore
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
        // 기기에서 값을 얻는 것들(위치)이 Context 를 필요로 한다.
        // **세션 복원보다 먼저** 물린다 — 아래 bind 가 ServiceLocator 에서 검증기를 꺼낸다
        ServiceLocator.bind(this)
        // 저장된 세션을 올린다. 시작 화면이 SessionStore.restored 를 기다린다 (SPEC §2.2)
        SessionStore.bind(
            persistence = DataStoreSessionPersistence(this),
            scope = appScope,
            // A0 — 디스크에 남은 토큰이 아직 쓸 수 있는지 서버에 물어본다
            validator = ServiceLocator.sessionValidator,
        )
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
