package com.shareguard.backend.payment;

import tools.jackson.databind.JsonNode;
import com.shareguard.backend.common.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
    private final PaymentTransactionService transactions;
    private final TossGateway toss;

    public PaymentService(PaymentTransactionService transactions, TossGateway toss) {
        this.transactions = transactions;
        this.toss = toss;
    }

    public PaymentDtos.PaymentResponse confirm(UUID actorId, PaymentDtos.ConfirmRequest request) {
        var order = transactions.prepareConfirmation(
            actorId, request.reservationId(), request.paymentKey(), request.orderId(), request.amount());
        if (order.status().equals("paid")) return transactions.responseFor(order);
        try {
            var response = toss.confirm(order.paymentKey(), order.orderId(), order.expectedAmount(), order.idempotencyKey());
            validateConfirmation(response, order);
            return transactions.completeConfirmation(order, response);
        } catch (RuntimeException exception) {
            transactions.failConfirmation(order, exception);
            throw exception;
        }
    }

    public PaymentDtos.PaymentResponse refund(UUID actorId, UUID reservationId, PaymentDtos.RefundRequest request) {
        var refund = transactions.prepareRefund(actorId, reservationId, request.amount());
        try {
            var response = toss.cancel(refund.paymentKey(), request.amount(), request.reason(), refund.idempotencyKey());
            validateRefund(response, refund, request.amount());
            return transactions.completeRefund(refund, request.amount(), response, actorId);
        } catch (RuntimeException exception) {
            transactions.failRefund(refund, exception);
            throw exception;
        }
    }

    public PaymentDtos.PaymentStateResponse state(UUID actorId, UUID reservationId) {
        return transactions.state(actorId, reservationId);
    }

    private void validateRefund(JsonNode response, PaymentTransactionService.PreparedRefund refund, int amount) {
        if (response == null || !refund.paymentKey().equals(response.path("paymentKey").asText())
            || !refund.orderId().equals(response.path("orderId").asText())
            || !java.util.Set.of("CANCELED", "PARTIAL_CANCELED").contains(response.path("status").asText())) {
            throw new ApiException(502, "결제사의 환급 응답 검증에 실패했습니다.");
        }
        long canceled = 0;
        for (JsonNode cancel : response.path("cancels")) {
            if (!"DONE".equals(cancel.path("cancelStatus").asText())) continue;
            canceled += cancel.path("cancelAmount").asLong(0);
        }
        if (canceled != (long) refund.refundedAmount() + amount) {
            throw new ApiException(502, "결제사의 환급 금액 확인이 필요합니다.");
        }
    }

    private void validateConfirmation(JsonNode response, PaymentTransactionService.PreparedPayment order) {
        if (response == null
            || !order.paymentKey().equals(response.path("paymentKey").asText())
            || !order.orderId().equals(response.path("orderId").asText())
            || order.expectedAmount() != response.path("totalAmount").asInt(-1)
            || !"DONE".equals(response.path("status").asText())) {
            throw new ApiException(502, "결제사의 승인 응답 검증에 실패했습니다.");
        }
    }
}
