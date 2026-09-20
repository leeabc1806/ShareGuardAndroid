package com.shareguard.backend.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.time.Instant;

public final class MfaDtos {
    private MfaDtos() {}

    /**
     * PortOne V2 returns identityVerificationId. imp_uid aliases are accepted only to ease
     * migration from PortOne V1 clients; new Android clients should send identityVerificationId.
     */
    public record VerifyRequest(
        String identityVerificationId,
        @JsonAlias({"imp_uid", "impUid"}) String legacyImpUid
    ) {
        public String resolvedVerificationId() {
            return identityVerificationId != null && !identityVerificationId.isBlank()
                ? identityVerificationId.trim()
                : legacyImpUid == null ? "" : legacyImpUid.trim();
        }
    }

    public record VerifyResponse(
        boolean verified,
        int trustScore,
        int trustBonusApplied,
        Instant verifiedAt
    ) {}
}
