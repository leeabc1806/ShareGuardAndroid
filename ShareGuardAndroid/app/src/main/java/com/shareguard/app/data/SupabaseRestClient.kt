package com.shareguard.app.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AuthResult(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val userId: String? = null,
    val email: String? = null,
    val message: String
) {
    val isSignedIn: Boolean get() = !accessToken.isNullOrBlank()
}

data class ProfileResult(
    val userId: String,
    val email: String,
    val displayName: String,
    val verified: Boolean
)

data class PassVerificationResult(
    val verified: Boolean,
    val message: String
)

data class RemoteItem(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val dailyPrice: Int,
    val deposit: Int,
    val address: String
)

data class NewRemoteItem(
    val title: String,
    val category: String,
    val description: String,
    val dailyPrice: Int,
    val deposit: Int,
    val address: String
)

class SupabaseRestClient(
    private val baseUrl: String,
    private val publishableKey: String
) {
    val isConfigured: Boolean =
        baseUrl.startsWith("https://") && publishableKey.startsWith("sb_publishable_")

    fun signIn(email: String, password: String): AuthResult {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
        return parseAuth(request("/auth/v1/token?grant_type=password", "POST", body))
    }

    fun signUp(email: String, password: String, displayName: String): AuthResult {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .put("data", JSONObject().put("display_name", displayName.trim()))
        return parseAuth(request("/auth/v1/signup", "POST", body))
    }

    fun googleSignInUrl(redirectUri: String, codeChallenge: String): String =
        Uri.parse(baseUrl.trimEnd('/') + "/auth/v1/authorize")
            .buildUpon()
            .appendQueryParameter("provider", "google")
            .appendQueryParameter("redirect_to", redirectUri)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "s256")
            .build()
            .toString()

    fun exchangeCodeForSession(authCode: String, codeVerifier: String): AuthResult {
        val body = JSONObject()
            .put("auth_code", authCode)
            .put("code_verifier", codeVerifier)
        return parseAuth(request("/auth/v1/token?grant_type=pkce", "POST", body))
    }

    fun refreshSession(refreshToken: String): AuthResult {
        val body = JSONObject().put("refresh_token", refreshToken)
        return parseAuth(request("/auth/v1/token?grant_type=refresh_token", "POST", body))
    }

    fun fetchProfile(accessToken: String): ProfileResult {
        val userResponse = request("/auth/v1/user", "GET", accessToken = accessToken)
        if (!userResponse.isSuccessful) throw IllegalStateException(userResponse.errorMessage())
        val user = JSONObject(userResponse.body)
        val userId = user.getString("id")
        val email = user.optString("email", "")

        val profileResponse = request(
            "/rest/v1/profiles?select=id,display_name,verified&id=eq.$userId&limit=1",
            "GET",
            accessToken = accessToken
        )
        if (!profileResponse.isSuccessful) throw IllegalStateException(profileResponse.errorMessage())
        val rows = JSONArray(profileResponse.body)
        val row = rows.optJSONObject(0)
        return ProfileResult(
            userId = userId,
            email = email,
            displayName = row?.optString("display_name")?.ifBlank { null }
                ?: email.substringBefore('@').ifBlank { "ShareGuard 사용자" },
            verified = row?.optBoolean("verified", false) ?: false
        )
    }

    fun verifyPass(
        identityVerificationId: String,
        accessToken: String,
        functionName: String
    ): PassVerificationResult {
        val response = request(
            "/functions/v1/$functionName",
            "POST",
            JSONObject().put("identityVerificationId", identityVerificationId),
            accessToken
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
        val json = JSONObject(response.body)
        return PassVerificationResult(
            verified = json.optBoolean("verified", false),
            message = json.optString("message", "PASS 본인인증 결과를 확인했습니다.")
        )
    }

    fun signOut(accessToken: String) {
        request("/auth/v1/logout", "POST", JSONObject(), accessToken)
    }

    fun fetchItems(accessToken: String? = null): List<RemoteItem> {
        val response = request(
            "/rest/v1/items?select=id,title,category,description,daily_price,deposit,address" +
                "&status=eq.active&order=created_at.desc",
            "GET",
            accessToken = accessToken
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())

        val rows = JSONArray(response.body)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            RemoteItem(
                id = row.getString("id"),
                title = row.getString("title"),
                category = row.optString("category", "기타"),
                description = row.optString("description", ""),
                dailyPrice = row.getInt("daily_price"),
                deposit = row.getInt("deposit"),
                address = row.optString("address", "거래 위치 협의")
            )
        }
    }

    fun insertItem(item: NewRemoteItem, accessToken: String): RemoteItem {
        val payload = JSONObject()
            .put("title", item.title)
            .put("category", item.category)
            .put("description", item.description)
            .put("daily_price", item.dailyPrice)
            .put("deposit", item.deposit)
            .put("address", item.address)
        val response = request(
            "/rest/v1/items",
            "POST",
            payload,
            accessToken,
            mapOf("Prefer" to "return=representation")
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
        val row = JSONArray(response.body).getJSONObject(0)
        return RemoteItem(
            id = row.getString("id"),
            title = row.getString("title"),
            category = row.optString("category", "기타"),
            description = row.optString("description", ""),
            dailyPrice = row.getInt("daily_price"),
            deposit = row.getInt("deposit"),
            address = row.optString("address", "거래 위치 협의")
        )
    }

    private fun parseAuth(response: HttpResponse): AuthResult {
        if (!response.isSuccessful) return AuthResult(message = response.errorMessage())
        val json = JSONObject(response.body)
        val token = json.optString("access_token").ifBlank { null }
        val refreshToken = json.optString("refresh_token").ifBlank { null }
        val user = json.optJSONObject("user")
        val userId = user?.optString("id")?.ifBlank { null }
        val email = user?.optString("email")?.ifBlank { null }
        val message = if (token == null) {
            "가입 확인 메일을 확인한 뒤 로그인하세요."
        } else {
            "로그인되었습니다. 거래 전 PASS 본인인증을 완료하세요."
        }
        return AuthResult(token, refreshToken, userId, email, message)
    }

    private fun request(
        path: String,
        method: String,
        body: JSONObject? = null,
        accessToken: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResponse {
        check(isConfigured) { "Supabase URL과 공개 키가 설정되지 않았습니다." }
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("apikey", publishableKey)
            connection.setRequestProperty("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
            }
            extraHeaders.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private data class HttpResponse(val status: Int, val body: String) {
        val isSuccessful: Boolean get() = status in 200..299

        fun errorMessage(): String {
            if (body.isBlank()) return "Supabase 요청 실패 (HTTP $status)"
            return runCatching {
                val json = JSONObject(body)
                json.optString("msg")
                    .ifBlank { json.optString("message") }
                    .ifBlank { json.optString("error_description") }
                    .ifBlank { json.optString("error") }
                    .ifBlank { body }
            }.getOrDefault(body)
        }
    }
}
