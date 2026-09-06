package com.shareguard.backend.payment;

import com.shareguard.backend.common.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping("/toss/confirm")
    PaymentDtos.PaymentResponse confirm(
        JwtAuthenticationToken authentication,
        @Valid @RequestBody PaymentDtos.ConfirmRequest request
    ) {
        return service.confirm(AuthenticatedUser.id(authentication), request);
    }

    @PostMapping("/{reservationId}/refund")
    PaymentDtos.PaymentResponse refund(
        JwtAuthenticationToken authentication,
        @PathVariable UUID reservationId,
        @Valid @RequestBody PaymentDtos.RefundRequest request
    ) {
        return service.refund(AuthenticatedUser.id(authentication), reservationId, request);
    }
}
