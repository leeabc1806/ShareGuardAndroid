package com.shareguard.backend.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shareguard.backend.common.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PaymentRefundTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID reservation = UUID.randomUUID();
    private PaymentTransactionService transactions;
    private TossGateway gateway;
    private PaymentService service;
    private PaymentTransactionService.PreparedRefund refund;

    @BeforeEach
    void setup() {
        transactions = mock(PaymentTransactionService.class);
        gateway = mock(TossGateway.class);
        service = new PaymentService(transactions, gateway);
        refund = new PaymentTransactionService.PreparedRefund(UUID.randomUUID(), reservation, actor,
            "order", "key", 12000, 2000, 10000, UUID.randomUUID());
        when(transactions.prepareRefund(actor, reservation, 8000)).thenReturn(refund);
    }

    private JsonNode payload(String paymentKey, String cancelStatus, int total) {
        return new ObjectMapper().readTree("""
            {"paymentKey":"%s","orderId":"order","status":"PARTIAL_CANCELED",
             "cancels":[{"cancelStatus":"%s","cancelAmount":%d}]}
            """.formatted(paymentKey, cancelStatus, total));
    }

    private void runRefund() {
        service.refund(actor, reservation, new PaymentDtos.RefundRequest(8000, "정상 반납 확인"));
    }

    @Test
    void commitsOnlyProviderConfirmedCumulativeAmount() {
        var json = payload("key", "DONE", 10000);
        when(gateway.cancel(anyString(), anyInt(), anyString(), any())).thenReturn(json);
        runRefund();
        verify(transactions).completeRefund(refund, 8000, json, actor);
    }

    @Test
    void pendingProviderCancelIsNotRefundCompleted() {
        when(gateway.cancel(anyString(), anyInt(), anyString(), any())).thenReturn(payload("key", "IN_PROGRESS", 10000));
        assertThrows(ApiException.class, this::runRefund);
        verify(transactions, never()).completeRefund(any(), anyInt(), any(), any());
        verify(transactions).failRefund(eq(refund), any(ApiException.class));
    }

    @Test
    void rejectsWrongPaymentKey() {
        when(gateway.cancel(anyString(), anyInt(), anyString(), any())).thenReturn(payload("different-key", "DONE", 10000));
        assertThrows(ApiException.class, this::runRefund);
        verify(transactions, never()).completeRefund(any(), anyInt(), any(), any());
    }

    @Test
    void rejectsWrongCumulativeRefundAmount() {
        when(gateway.cancel(anyString(), anyInt(), anyString(), any())).thenReturn(payload("key", "DONE", 8000));
        assertThrows(ApiException.class, this::runRefund);
        verify(transactions, never()).completeRefund(any(), anyInt(), any(), any());
    }

    @Test
    void networkFailureNeverCompletesRefund() {
        when(gateway.cancel(anyString(), anyInt(), anyString(), any())).thenThrow(new IllegalStateException("timeout"));
        assertThrows(IllegalStateException.class, this::runRefund);
        verify(transactions, never()).completeRefund(any(), anyInt(), any(), any());
        verify(transactions).failRefund(eq(refund), any(IllegalStateException.class));
    }

    @Test
    void timedOutRetryUsesSameProviderKeyButNextRefundUsesDifferentKey() {
        UUID id = UUID.randomUUID();
        assertEquals(PaymentTransactionService.refundKey(id, 0, 8000), PaymentTransactionService.refundKey(id, 0, 8000));
        assertNotEquals(PaymentTransactionService.refundKey(id, 0, 8000), PaymentTransactionService.refundKey(id, 8000, 2000));
    }
}
