package com.shareguard.backend.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shareguard.backend.common.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PaymentServiceTest {
    private PaymentTransactionService transactions;
    private TossGateway toss;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        transactions = mock(PaymentTransactionService.class);
        toss = mock(TossGateway.class);
        service = new PaymentService(transactions, toss);
    }

    @Test
    void confirmsOnlyAfterProviderResponseMatchesOrder() throws Exception {
        var actorId = UUID.randomUUID();
        var reservationId = UUID.randomUUID();
        var idempotencyKey = UUID.randomUUID();
        var prepared = new PaymentTransactionService.PreparedPayment(
            UUID.randomUUID(), reservationId, actorId, "shareguard-order-123", "payment-key", 12000, 0, "confirming", idempotencyKey);
        var provider = new ObjectMapper().readTree("""
            {"orderId":"shareguard-order-123","totalAmount":12000,"status":"DONE","paymentKey":"payment-key"}
            """);
        var expected = new PaymentDtos.PaymentResponse(prepared.id(), reservationId, prepared.orderId(), "paid", 12000, 0);
        when(transactions.prepareConfirmation(actorId, reservationId, "payment-key", prepared.orderId(), 12000)).thenReturn(prepared);
        when(toss.confirm("payment-key", prepared.orderId(), 12000, idempotencyKey)).thenReturn(provider);
        when(transactions.completeConfirmation(prepared, provider)).thenReturn(expected);

        var actual = service.confirm(actorId, new PaymentDtos.ConfirmRequest(reservationId, "payment-key", prepared.orderId(), 12000));

        assertEquals(expected, actual);
    }

    @Test
    void rejectsMismatchedProviderAmountAndRecordsFailure() throws Exception {
        var actorId = UUID.randomUUID();
        var reservationId = UUID.randomUUID();
        var prepared = new PaymentTransactionService.PreparedPayment(
            UUID.randomUUID(), reservationId, actorId, "shareguard-order-123", "payment-key", 12000, 0, "confirming", UUID.randomUUID());
        var provider = new ObjectMapper().readTree("""
            {"orderId":"shareguard-order-123","totalAmount":1,"status":"DONE","paymentKey":"payment-key"}
            """);
        when(transactions.prepareConfirmation(eq(actorId), eq(reservationId), any(), any(), eq(12000))).thenReturn(prepared);
        when(toss.confirm(any(), any(), eq(12000), any())).thenReturn(provider);

        assertThrows(ApiException.class,
            () -> service.confirm(actorId, new PaymentDtos.ConfirmRequest(reservationId, "payment-key", prepared.orderId(), 12000)));
        verify(transactions).failConfirmation(eq(prepared), any(ApiException.class));
    }
}
