package com.shareguard.backend.payment;

import tools.jackson.databind.JsonNode;
import com.shareguard.backend.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TossWebhookService {
    private final String pathToken;
    private final TossGateway toss;
    private final TossWebhookTransactionService events;
    private final PaymentTransactionService payments;

    public TossWebhookService(
        @Value("${shareguard.webhook.path-token}") String pathToken,
        TossGateway toss,
        TossWebhookTransactionService events,
        PaymentTransactionService payments
    ) {
        this.pathToken = pathToken;
        this.toss = toss;
        this.events = events;
        this.payments = payments;
    }

    public void accept(String suppliedToken, String transmissionId, JsonNode webhook) {
        requireValidToken(suppliedToken);
        if (transmissionId == null || transmissionId.isBlank()) throw new ApiException(400, "웹훅 전송 ID가 없습니다.");
        String eventType = webhook.path("eventType").asText("UNKNOWN");
        if (!events.begin(transmissionId, eventType, webhook)) return;
        try {
            String paymentKey = webhook.path("data").path("paymentKey").asText();
            if (!paymentKey.isBlank()) {
                JsonNode verified = toss.find(paymentKey);
                payments.reconcileVerifiedProviderState(verified);
            }
            events.complete(transmissionId);
        } catch (RuntimeException exception) {
            events.fail(transmissionId, exception);
            throw exception;
        }
    }

    private void requireValidToken(String suppliedToken) {
        if (pathToken.equals("change-me") || pathToken.length() < 24) {
            throw new ApiException(503, "안전한 TOSS_WEBHOOK_PATH_TOKEN 설정이 필요합니다.");
        }
        boolean matches = MessageDigest.isEqual(pathToken.getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8));
        if (!matches) throw new ApiException(404, "웹훅 경로를 찾을 수 없습니다.");
    }
}
