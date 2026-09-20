package com.shareguard.app.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object MfaApiFactory {
    fun create(baseUrl: String): MfaApi {
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
            "SHAREGUARD_API_BASE_URL이 설정되지 않았습니다."
        }
        val normalized = if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MfaApi::class.java)
    }
}
