package com.shareguard.app.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TossConfirmClient(private val confirmUrl: String) {
    val isConfigured: Boolean get() = confirmUrl.startsWith("https://") || confirmUrl.startsWith("http://")

    fun confirm(paymentKey: String, orderId: String, amount: Long, reservationId: String, accessToken: String): String {
        check(isConfigured) { "TOSS_CONFIRM_URL이 설정되지 않았습니다." }
        require(paymentKey.isNotBlank() && orderId.isNotBlank() && amount > 0 && amount <= Int.MAX_VALUE) { "결제 인증 결과가 올바르지 않습니다." }
        val connection = URL(confirmUrl).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")

            val body = JSONObject()
                .put("reservationId", reservationId)
                .put("paymentKey", paymentKey)
                .put("orderId", orderId)
                .put("amount", amount)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException(errorMessage(status, responseBody))
            }
            val payment = JSONObject(responseBody)
            check(payment.getString("reservationId") == reservationId &&
                payment.getString("orderId") == orderId && payment.getString("status") == "paid" &&
                payment.getLong("approvedAmount") == amount) { "서버 결제 승인 결과가 주문과 일치하지 않습니다." }
            responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(status: Int, body: String): String {
        if (body.isBlank()) return "결제 승인 실패 (HTTP $status)"
        return runCatching {
            val json = JSONObject(body)
            json.optString("message")
                .ifBlank { json.optString("error") }
                .ifBlank { body }
        }.getOrDefault(body)
    }
}
