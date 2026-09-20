package com.shareguard.backend.auth;

import com.shareguard.backend.common.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/mfa")
public class MfaController {
    private final MfaService service;

    public MfaController(MfaService service) {
        this.service = service;
    }

    @PostMapping("/verify")
    MfaDtos.VerifyResponse verify(
        JwtAuthenticationToken authentication,
        @Valid @RequestBody MfaDtos.VerifyRequest request
    ) {
        return service.verify(AuthenticatedUser.id(authentication), request);
    }
}
