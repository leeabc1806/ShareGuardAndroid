package com.shareguard.backend.payment;

import tools.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/toss")
public class TossWebhookController {
    private final TossWebhookService service;

    public TossWebhookController(TossWebhookService service) {
        this.service = service;
    }

    @PostMapping("/{pathToken}")
    Map<String, Boolean> receive(
        @PathVariable String pathToken,
        @RequestHeader(value = "tosspayments-webhook-transmission-id", required = false) String transmissionId,
        @RequestBody JsonNode payload
    ) {
        service.accept(pathToken, transmissionId, payload);
        return Map.of("accepted", true);
    }
}
