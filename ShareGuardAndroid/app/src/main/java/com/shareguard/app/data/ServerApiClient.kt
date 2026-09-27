package com.shareguard.app.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.UUID

data class IssuedQr(val payload: String, val expiresAt: Instant)
data class VerifiedQr(val contractId: String, val action: String, val contractStatus: String)
data class PaymentState(
    val reservationId: String,
    val status: String,
    val approvedAmount: Int,
    val refundedAmount: Int,
    val refundableAmount: Int,
    val refundAllowed: Boolean
) {
    val refunded: Boolean get() = status == "refunded"

    companion object {
        fun parse(json: JSONObject) = PaymentState(
            json.getString("reservationId"), json.getString("status"),
            json.getInt("approvedAmount"), json.getInt("refundedAmount"),
            json.optInt("refundableAmount", 0), json.optBoolean("refundAllowed", false)
        )
    }
}

/** Money and QR mutations must pass through the authenticated Spring server. */
class ServerApiClient(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')
    val isConfigured: Boolean get() = runCatching {
        val uri = URI(base)
        uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null
    }.getOrDefault(false)

    fun issueQr(contractId: String, action: String, token: String): IssuedQr {
        UUID.fromString(contractId)
        require(action in setOf("start", "end"))
        val json = request("api/v1/qr/issue", "POST", token,
            JSONObject().put("contractId", contractId).put("action", action))
        return IssuedQr(json.getString("payload"), Instant.parse(json.getString("expiresAt")))
    }

    fun verifyQr(payload: String, contractId: String, action: String, token: String,
                 latitude: Double?, longitude: Double?): VerifiedQr {
        val json = request("api/v1/qr/verify", "POST", token, JSONObject()
            .put("payload", payload).put("contractId", contractId).put("action", action)
            .put("latitude", latitude ?: JSONObject.NULL).put("longitude", longitude ?: JSONObject.NULL))
        val result = VerifiedQr(json.getString("contractId"), json.getString("action"), json.getString("contractStatus"))
        check(result.contractId == contractId && result.action == action &&
            result.contractStatus == if (action == "start") "active" else "completed") { "QR 처리 결과를 다시 확인해주세요." }
        return result
    }

    fun payment(reservationId: String, token: String): PaymentState {
        UUID.fromString(reservationId)
        return PaymentState.parse(request("api/v1/payments/$reservationId", "GET", token))
    }

    fun refund(reservationId: String, amount: Int, token: String): PaymentState {
        UUID.fromString(reservationId)
        require(amount > 0) { "환급할 보증금이 없습니다." }
        return PaymentState.parse(request("api/v1/payments/$reservationId/refund", "POST", token,
            JSONObject().put("amount", amount).put("reason", "반납 확인 후 보증금 환급")))
    }

    fun resolveReport(reportId: String, decision: String, amount: Int, note: String, sanction: String, token: String) {
        UUID.fromString(reportId)
        request("api/v1/admin/reports/$reportId/resolve", "POST", token, JSONObject()
            .put("decision", decision).put("deductionAmount", amount).put("note", note).put("sanction", sanction))
    }

    private fun request(path: String, method: String, token: String, body: JSONObject? = null): JSONObject {
        check(isConfigured) { "서버 주소를 설정해주세요." }
        require(token.isNotBlank()) { "로그인이 필요합니다." }
        val connection = URL("$base/$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            check(status in 200..299) {
                json?.optString("message")?.takeIf { it.isNotBlank() } ?: "서버 요청 실패 (HTTP $status)"
            }
            json ?: error("서버 응답 형식을 확인할 수 없습니다.")
        } finally {
            connection.disconnect()
        }
    }
}
