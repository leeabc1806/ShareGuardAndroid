package com.shareguard.backend.auth;

public interface PortOneIdentityVerifier {
    VerifiedIdentity verify(String identityVerificationId);

    record VerifiedIdentity(
        String identityVerificationId,
        String name,
        String phoneNumber,
        String birthDate,
        String ci,
        String di
    ) {}
}
