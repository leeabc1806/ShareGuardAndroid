package com.shareguard.backend.qr;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

public final class QrDtos {
    private QrDtos() {}

    public record IssueRequest(
        @NotNull UUID contractId,
        @NotBlank @Pattern(regexp = "start|end") String action
    ) {}

    public record IssueResponse(String payload, Instant expiresAt) {}

    public record VerifyRequest(
        @NotBlank String payload,
        @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @DecimalMin("-180") @DecimalMax("180") Double longitude
    ) {}

    public record VerifyResponse(UUID contractId, String action, String contractStatus, Instant verifiedAt) {}
}
