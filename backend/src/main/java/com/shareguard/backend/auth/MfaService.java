package com.shareguard.backend.auth;

import com.shareguard.backend.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MfaService {
    private final PortOneIdentityVerifier portOne;
    private final MfaVerificationRepository repository;
    private final int trustBonus;

    public MfaService(
        PortOneIdentityVerifier portOne,
        MfaVerificationRepository repository,
        @Value("${shareguard.mfa.trust-bonus:150}") int trustBonus
    ) {
        this.portOne = portOne;
        this.repository = repository;
        this.trustBonus = Math.max(0, trustBonus);
    }

    @Transactional
    public MfaDtos.VerifyResponse verify(UUID userId, MfaDtos.VerifyRequest request) {
        var verificationId = request.resolvedVerificationId();
        if (verificationId.isBlank() || verificationId.length() > 200) {
            throw new ApiException(400, "identityVerificationId가 필요합니다.");
        }

        // A client callback is never trusted. The server always reads the final status from PortOne.
        var identity = portOne.verify(verificationId);
        var identityHash = fingerprint(identity);
        var existingUser = repository.userIdForIdentityHash(identityHash);
        if (existingUser.isPresent() && !existingUser.get().equals(userId)) {
            throw new ApiException(409, "이미 다른 계정에서 사용한 본인인증 정보입니다.");
        }

        var profile = repository.lockProfile(userId);
        if (profile.verified()) {
            return new MfaDtos.VerifyResponse(true, profile.trustScore(), 0, profile.verifiedAt());
        }

        var verifiedAt = Instant.now();
        var trustScore = Math.min(1000, profile.trustScore() + trustBonus);
        var phone = identity.phoneNumber();
        var phoneLast4 = phone.substring(Math.max(0, phone.length() - 4));
        try {
            repository.insertVerification(
                userId, identity.identityVerificationId(), identityHash, phoneLast4,
                profile.trustScore(), trustScore, verifiedAt
            );
        } catch (DataIntegrityViolationException conflict) {
            // The DB uniqueness constraints are the final protection against two concurrent
            // requests binding the same PortOne identity to different accounts.
            throw new ApiException(409, "이미 처리되었거나 다른 계정에서 사용한 본인인증 정보입니다.");
        }
        repository.markVerified(userId, trustScore, verifiedAt);
        return new MfaDtos.VerifyResponse(true, trustScore, trustScore - profile.trustScore(), verifiedAt);
    }

    private String fingerprint(PortOneIdentityVerifier.VerifiedIdentity identity) {
        var stableIdentity = !identity.ci().isBlank() ? "ci:" + identity.ci()
            : !identity.di().isBlank() ? "di:" + identity.di()
            : "fallback:" + identity.name() + "|" + identity.birthDate() + "|" + identity.phoneNumber();
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(stableIdentity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
