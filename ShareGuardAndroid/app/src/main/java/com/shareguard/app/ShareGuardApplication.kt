package com.shareguard.app

import android.app.Application
import android.os.Build
import android.util.Log
import com.kakao.vectormap.KakaoMapSdk

class ShareGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (supportsKakaoMaps() && BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()) {
            try {
                KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
            } catch (error: UnsatisfiedLinkError) {
                Log.e(TAG, "Kakao Maps native library is unavailable on this device", error)
            }
        }
    }

    companion object {
        private const val TAG = "ShareGuardApplication"

        fun supportsKakaoMaps(): Boolean = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a", "armeabi-v7a" -> true
            else -> false
        }
    }
}
