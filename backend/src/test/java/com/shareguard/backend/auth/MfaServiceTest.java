package com.shareguard.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shareguard.backend.common.ApiException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MfaServiceTest {
    private PortOneIdentityVerifier portOne;
    private MfaVerificationRepository repository;
    private MfaService service;

    @BeforeEach
    void setUp() {
        portOne = mock(PortOneIdentityVerifier.class);
        repository = mock(MfaVerificationRepository.class);
        service = new MfaService(portOne, repository, 150);
    }

    @Test
    void verifiesWithPortOneAndAddsTrustBonusOnlyOnFirstSuccess() {
        var userId = UUID.randomUUID();
        var verificationId = "identity-verification-test-1";
        when(portOne.verify(verificationId)).thenReturn(identity(verificationId));
        when(repository.userIdForIdentityHash(anyString())).thenReturn(Optional.empty());
        when(repository.lockProfile(userId))
            .thenReturn(new MfaVerificationRepository.ProfileVerification(false, 500, null));

        var response = service.verify(userId, new MfaDtos.VerifyRequest(verificationId, null));

        assertEquals(true, response.verified());
        assertEquals(650, response.trustScore());
        assertEquals(150, response.trustBonusApplied());
        verify(repository).insertVerification(
            eq(userId), eq(verificationId), anyString(), eq("5678"),
            eq(500), eq(650), any(Instant.class)
        );
        verify(repository).markVerified(eq(userId), eq(650), any(Instant.class));
    }

    @Test
    void returnsExistingScoreWithoutAddingBonusAgain() {
        var userId = UUID.randomUUID();
        var verificationId = "identity-verification-test-2";
        var verifiedAt = Instant.parse("2026-01-02T03:04:05Z");
        when(portOne.verify(verificationId)).thenReturn(identity(verificationId));
        when(repository.userIdForIdentityHash(anyString())).thenReturn(Optional.of(userId));
        when(repository.lockProfile(userId))
            .thenReturn(new MfaVerificationRepository.ProfileVerification(true, 550, verifiedAt));

        var response = service.verify(userId, new MfaDtos.VerifyRequest(verificationId, null));

        assertEquals(550, response.trustScore());
        assertEquals(0, response.trustBonusApplied());
        assertEquals(verifiedAt, response.verifiedAt());
        verify(repository, never()).markVerified(any(), anyInt(), any());
        verify(repository, never()).insertVerification(any(), anyString(), anyString(), anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void rejectsIdentityAlreadyBoundToAnotherAccount() {
        var userId = UUID.randomUUID();
        var verificationId = "identity-verification-test-3";
        when(portOne.verify(verificationId)).thenReturn(identity(verificationId));
        when(repository.userIdForIdentityHash(anyString())).thenReturn(Optional.of(UUID.randomUUID()));

        var error = assertThrows(
            ApiException.class,
            () -> service.verify(userId, new MfaDtos.VerifyRequest(verificationId, null))
        );

        assertEquals(409, error.status());
        verify(repository, never()).lockProfile(any());
    }

    private PortOneIdentityVerifier.VerifiedIdentity identity(String verificationId) {
        return new PortOneIdentityVerifier.VerifiedIdentity(
            verificationId,
            "홍길동",
            "01012345678",
            "1995-01-01",
            "test-ci",
            "test-di"
        );
    }
}
