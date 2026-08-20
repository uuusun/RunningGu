package com.runninggu.app

import android.app.Application
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
    }
}
