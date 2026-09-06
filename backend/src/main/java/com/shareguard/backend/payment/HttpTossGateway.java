package com.shareguard.backend.payment;

import tools.jackson.databind.JsonNode;
import com.shareguard.backend.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpTossGateway implements TossGateway {
    private final RestClient client;
    private final String authorization;

    public HttpTossGateway(
        RestClient.Builder builder,
        @Value("${shareguard.toss.base-url}") String baseUrl,
        @Value("${shareguard.toss.secret-key}") String secretKey
    ) {
        this.client = builder.baseUrl(baseUrl).build();
        this.authorization = secretKey.isBlank() ? "" : "Basic " + Base64.getEncoder()
            .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public JsonNode confirm(String paymentKey, String orderId, int amount, UUID idempotencyKey) {
        return post("/v1/payments/confirm", Map.of(
            "paymentKey", paymentKey,
            "orderId", orderId,
            "amount", amount
        ), idempotencyKey);
    }

    @Override
    public JsonNode cancel(String paymentKey, int amount, String reason, UUID idempotencyKey) {
        return post("/v1/payments/{paymentKey}/cancel", Map.of(
            "cancelReason", reason,
            "cancelAmount", amount
        ), idempotencyKey, paymentKey);
    }

    @Override
    public JsonNode find(String paymentKey) {
        requireConfigured();
        try {
            return client.get().uri("/v1/payments/{paymentKey}", paymentKey)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            throw tossError(exception);
        }
    }

    private JsonNode post(String path, Map<String, ?> body, UUID idempotencyKey, Object... uriVariables) {
        requireConfigured();
        try {
            return client.post().uri(path, uriVariables)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header("Idempotency-Key", idempotencyKey.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            throw tossError(exception);
        }
    }

    private void requireConfigured() {
        if (authorization.isBlank()) throw new ApiException(503, "TOSS_SECRET_KEY가 설정되지 않았습니다.");
    }

    private ApiException tossError(RestClientResponseException exception) {
        var body = exception.getResponseBodyAsString();
        return new ApiException(exception.getStatusCode().value(), body.isBlank() ? "토스페이먼츠 요청에 실패했습니다." : body);
    }
}
