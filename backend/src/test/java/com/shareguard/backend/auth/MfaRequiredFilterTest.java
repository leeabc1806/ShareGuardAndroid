package com.shareguard.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

class MfaRequiredFilterTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsItemCreationWithStructuredForbiddenResponse() throws Exception {
        var userId = authenticate();
        var repository = mock(MfaVerificationRepository.class);
        var chain = mock(FilterChain.class);
        var response = new MockHttpServletResponse();
        when(repository.isVerified(userId)).thenReturn(false);

        new MfaRequiredFilter(repository, new ObjectMapper()).doFilter(
            new MockHttpServletRequest("POST", "/api/v1/items"), response, chain
        );

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("MFA_REQUIRED"));
        verifyNoInteractions(chain);
    }

    @Test
    void allowsVerifiedUserToReachPaymentController() throws Exception {
        var userId = authenticate();
        var repository = mock(MfaVerificationRepository.class);
        var chain = mock(FilterChain.class);
        var request = new MockHttpServletRequest("POST", "/api/v1/payments/toss/confirm");
        var response = new MockHttpServletResponse();
        when(repository.isVerified(userId)).thenReturn(true);

        new MfaRequiredFilter(repository, new ObjectMapper()).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private UUID authenticate() {
        var userId = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("test-token")
            .header("alg", "RS256")
            .subject(userId.toString())
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return userId;
    }
}
