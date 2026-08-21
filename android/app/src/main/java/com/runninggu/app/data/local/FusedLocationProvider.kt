package com.runninggu.app.data.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.runninggu.app.domain.LatLng
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * FusedLocationProvider 로 한 번 조회한다. (SPEC §4.11-1 ①)
 *
 * `lastLocation` 이 아니라 `getCurrentLocation` 을 쓴다. 마지막으로 알려진 위치는 며칠 전
 * 다른 도시의 것일 수 있는데, 그걸 출발지로 잡으면 **엉뚱한 동네 코스를 추천**하게 된다.
 *
 * 정확도는 `BALANCED_POWER` 로 둔다. 반경 8km 안에서 코스를 찾는 용도라 몇십 미터 차이는
 * 결과를 바꾸지 않는데, 고정밀은 실내에서 6초를 넘기기 쉽다.
 */
class FusedLocationProvider(context: Context) : LocationProvider {

    // Application context 를 쓴다. Activity 를 붙들면 화면 회전에서 샌다
    private val appContext = context.applicationContext
    private val client by lazy { LocationServices.getFusedLocationProviderClient(appContext) }

    override suspend fun current(): LocationResult {
        if (!hasPermission()) return LocationResult.PermissionDenied

        val tokenSource = CancellationTokenSource()
        val result = withTimeoutOrNull(LocationProvider.TIMEOUT_MS) {
            requestLocation(tokenSource)
        }
        // 시간이 지났으면 기기에 더 찾지 말라고 알린다 — 안 하면 배터리를 계속 쓴다
        if (result == null) tokenSource.cancel()

        return result ?: LocationResult.Timeout
    }

    private suspend fun requestLocation(tokenSource: CancellationTokenSource): LocationResult =
        suspendCancellableCoroutine { continuation ->
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setDurationMillis(LocationProvider.TIMEOUT_MS)
                .build()

            continuation.invokeOnCancellation { tokenSource.cancel() }

            try {
                client.getCurrentLocation(request, tokenSource.token)
                    .addOnSuccessListener { location ->
                        // 성공했는데 null 이 온다 — 위치 서비스가 꺼져 있을 때다
                        continuation.resumeIfActive(
                            if (location == null) {
                                LocationResult.Unavailable
                            } else {
                                LocationResult.Found(LatLng(location.latitude, location.longitude))
                            },
                        )
                    }
                    .addOnFailureListener {
                        continuation.resumeIfActive(LocationResult.Unavailable)
                    }
            } catch (e: SecurityException) {
                // 확인과 호출 사이에 권한이 회수될 수 있다. 앱을 죽이지 않는다
                continuation.resumeIfActive(LocationResult.PermissionDenied)
            }
        }

    private fun hasPermission(): Boolean =
        LOCATION_PERMISSIONS.any { permission ->
            ContextCompat.checkSelfPermission(appContext, permission) ==
                PackageManager.PERMISSION_GRANTED
        }

    private companion object {
        /**
         * 둘 중 **하나만 있어도 된다.** 코스 추천은 반경 8km 기준이라 대략의 위치로 충분하다
         * — 정밀 위치를 거부하고 대략 위치만 준 사용자를 막을 이유가 없다(NFR-15).
         */
        val LOCATION_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}

/** 이미 끝난 continuation 에 두 번 값을 넣으면 터진다. 콜백이 겹칠 수 있어 막아 둔다. */
private fun CancellableContinuation<LocationResult>.resumeIfActive(result: LocationResult) {
    if (isActive) resume(result)
}
