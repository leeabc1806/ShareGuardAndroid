package com.shareguard.backend.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class PaymentDtos {
    private PaymentDtos() {}

    public record ConfirmRequest(
        @NotNull UUID reservationId,
        @NotBlank @Size(max = 200) String paymentKey,
        @NotBlank @Size(min = 10, max = 100) String orderId,
        @Positive int amount
    ) {}

    public record RefundRequest(
        @Positive int amount,
        @NotBlank @Size(min = 5, max = 200) String reason
    ) {}

    public record PaymentResponse(
        UUID paymentOrderId,
        UUID reservationId,
        String orderId,
        String status,
        int approvedAmount,
        int refundedAmount
    ) {}

    public record PaymentStateResponse(
        UUID reservationId, String status, int approvedAmount, int refundedAmount,
        int refundableAmount, boolean refundAllowed
    ) {}
}
