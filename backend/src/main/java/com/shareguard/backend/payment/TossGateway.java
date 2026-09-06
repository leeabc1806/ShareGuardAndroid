package com.shareguard.backend.payment;

import tools.jackson.databind.JsonNode;
import java.util.UUID;

public interface TossGateway {
    JsonNode confirm(String paymentKey, String orderId, int amount, UUID idempotencyKey);
    JsonNode cancel(String paymentKey, int amount, String reason, UUID idempotencyKey);
    JsonNode find(String paymentKey);
}
