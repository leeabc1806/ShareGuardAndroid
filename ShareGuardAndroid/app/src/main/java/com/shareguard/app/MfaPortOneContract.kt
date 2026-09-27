package com.shareguard.app

import org.json.JSONObject
import java.net.URI

/** PortOne requires an HTTP(S) redirectUrl for identity verification, not an app scheme. */
internal object MfaPortOneContract {
    const val CALLBACK_SCHEME = "shareguard"
    const val CALLBACK_HOST = "mfa-complete"
    const val WEB_ORIGIN = "https://shareguard.invalid/"
    const val REDIRECT_URL = "${WEB_ORIGIN}mfa-complete"

    fun createRequest(storeId: String, channelKey: String, identityVerificationId: String): JSONObject =
        JSONObject()
            .put("storeId", storeId)
            .put("channelKey", channelKey)
            .put("identityVerificationId", identityVerificationId)
            // Native-only sentinel, as in PortOne's mobile SDK: intercept BEFORE loading it.
            // .invalid intentionally prevents sending results to an unrelated public domain.
            .put("redirectUrl", REDIRECT_URL)
            .put("forceRedirect", true)
            .put("windowType", JSONObject().put("mobile", "REDIRECTION"))
            .put("bypass", JSONObject()
                .put("danal", JSONObject().put("CPTITLE", "ShareGuard"))
                .put("inicisUnified", JSONObject()
                    .put("flgFixedUser", "N")
                    .put("directAgency", "PASS")))

    fun isCallbackUrl(rawUrl: String?): Boolean {
        if (rawUrl == null) return false
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null || uri.rawFragment != null) return false
        return when {
            uri.scheme.equals("https", ignoreCase = true) ->
                uri.host.equals("shareguard.invalid", ignoreCase = true) &&
                    uri.port in setOf(-1, 443) && uri.rawPath == "/mfa-complete"
            uri.scheme.equals(CALLBACK_SCHEME, ignoreCase = true) ->
                uri.host.equals(CALLBACK_HOST, ignoreCase = true) &&
                    uri.port == -1 && uri.rawPath in setOf("", "/")
            else -> false
        }
    }
}
