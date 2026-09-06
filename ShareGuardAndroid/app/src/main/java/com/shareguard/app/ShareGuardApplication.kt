package com.shareguard.app

import android.app.Application
import com.kakao.vectormap.KakaoMapSdk

class ShareGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()) {
            KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        }
    }
}
