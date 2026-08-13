package com.shareguard.app.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TossConfirmClient(private val confirmUrl: String) {
    val isConfigured: Boolean get() = confirmUrl.startsWith("https://") || confirmUrl.startsWith("http://")

    fun confirm(paymentKey: String, orderId: String, amount: Long): String {
        check(isConfigured) { "TOSS_CONFIRM_URL이 설정되지 않았습니다." }
        val connection = URL(confirmUrl).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            val body = JSONObject()
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
