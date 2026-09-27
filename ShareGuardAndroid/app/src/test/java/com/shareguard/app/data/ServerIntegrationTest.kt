package com.shareguard.app.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class ServerIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ServerApiClient
    private val id = UUID.randomUUID().toString()

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        client = ServerApiClient(server.url("/").toString())
    }
    @After fun cleanup() = server.shutdown()
    private fun reply(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body).addHeader("Content-Type", "application/json"))
    }
    private fun payment(status: String, refunded: Int = 0) =
        """{"reservationId":"$id","status":"$status","approvedAmount":12000,"refundedAmount":$refunded,"refundableAmount":10000,"refundAllowed":true}"""

    @Test fun qrIssueUsesServerWithBearerToken() {
        reply("""{"payload":"server-issued-token","expiresAt":"2030-01-01T00:00:00Z"}""")
        assertEquals("server-issued-token", client.issueQr(id, "start", "session").payload)
        val request = server.takeRequest()
        assertEquals("/api/v1/qr/issue", request.path)
        assertEquals("Bearer session", request.getHeader("Authorization"))
        assertEquals(id, JSONObject(request.body.readUtf8()).getString("contractId"))
    }

    @Test fun verifySendsSelectedContractAndAction() {
        reply("""{"contractId":"$id","action":"end","contractStatus":"completed"}""")
        client.verifyQr("token", id, "end", "session", null, null)
        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals("/api/v1/qr/verify", request.path)
        assertEquals(id, body.getString("contractId"))
        assertEquals("end", body.getString("action"))
    }

    @Test fun mismatchedQrResponseIsRejected() {
        reply("""{"contractId":"wrong","action":"end","contractStatus":"completed"}""")
        assertThrows(IllegalStateException::class.java) { client.verifyQr("token", id, "end", "session", null, null) }
    }

    @Test fun qrReuseErrorDoesNotProduceSuccess() {
        reply("""{"message":"이미 사용된 QR입니다."}""", 409)
        assertThrows(IllegalStateException::class.java) { client.verifyQr("token", id, "start", "session", null, null) }
    }

    @Test fun returnedButUnrefundedPaymentStaysUnrefunded() {
        reply(payment("paid"))
        val state = client.payment(id, "session")
        assertFalse(state.refunded)
        assertEquals(0, state.refundedAmount)
    }

    @Test fun refundCallsServerAndReadsActualPartialAmount() {
        reply(payment("partially_refunded", 4000))
        val state = client.refund(id, 4000, "session")
        assertFalse(state.refunded)
        assertEquals(4000, state.refundedAmount)
        val request = server.takeRequest()
        assertEquals("/api/v1/payments/$id/refund", request.path)
        assertEquals(4000, JSONObject(request.body.readUtf8()).getInt("amount"))
    }

    @Test fun refundFailureIsNotTreatedAsRefunded() {
        reply("""{"message":"provider unavailable"}""", 502)
        assertThrows(IllegalStateException::class.java) { client.refund(id, 10000, "session") }
    }

    @Test fun paymentApprovalRequiresMatchingServerOrder() {
        reply("""{"reservationId":"$id","orderId":"other-order","status":"paid","approvedAmount":12000}""")
        val confirm = TossConfirmClient(server.url("/api/v1/payments/toss/confirm").toString())
        assertThrows(IllegalStateException::class.java) { confirm.confirm("key", "order", 12000, id, "session") }
    }

    @Test fun paymentApprovalSendsReservationAndChecksAmount() {
        reply("""{"reservationId":"$id","orderId":"order","status":"paid","approvedAmount":12000}""")
        TossConfirmClient(server.url("/api/v1/payments/toss/confirm").toString()).confirm("key", "order", 12000, id, "session")
        val request = server.takeRequest()
        assertEquals("Bearer session", request.getHeader("Authorization"))
        assertEquals(id, JSONObject(request.body.readUtf8()).getString("reservationId"))
    }

    @Test fun absentApprovalUrlCannotFallBackToDemoPayment() {
        assertThrows(IllegalStateException::class.java) { TossConfirmClient("").confirm("key", "order", 12000, id, "session") }
        assertEquals(0, server.requestCount)
    }
}
