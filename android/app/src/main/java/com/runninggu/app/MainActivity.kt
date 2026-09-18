package com.runninggu.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.runninggu.app.ui.RunningGuApp
import com.runninggu.app.ui.theme.RunningGuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 앱에 다크 테마가 없다 — 어떤 시스템 설정에서도 화면은 라이트다. 기본
        // `enableEdgeToEdge()` 는 **시스템 다크 여부**로 상태바 아이콘 색을 정하므로,
        // 시스템 다크에서 흰 아이콘이 흰 배경 위에 얹혀 시계·배터리가 사라졌다(#362 · QA A-06).
        // 그래서 상태바만 라이트로 못 박는다 — scrim 은 기본값과 같은 투명이다.
        // 홈 히어로는 상태바 뒤까지 어둡게 깔리므로 `HomeHero` 가 그 화면에서만 뒤집고
        // 벗어날 때 되돌린다.
        // 하단 내비게이션 바는 기본 `auto` 그대로 둔다 — 그쪽은 scrim 이 투명이 아니라
        // 시스템 다크에서 어두운 배경이 함께 깔려 아이콘이 보인다.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            RunningGuTheme {
                RunningGuApp()
            }
        }
    }
}
