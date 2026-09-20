package com.shareguard.app.data

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

data class MfaVerifyRequest(
    @SerializedName("identityVerificationId") val identityVerificationId: String
)

data class MfaVerifyResponse(
    val verified: Boolean,
    val trustScore: Int,
    val trustBonusApplied: Int,
    val verifiedAt: String?
)

interface MfaApi {
    @POST("api/v1/auth/mfa/verify")
    suspend fun verify(
        @Header("Authorization") authorization: String,
        @Body request: MfaVerifyRequest
    ): MfaVerifyResponse
}
