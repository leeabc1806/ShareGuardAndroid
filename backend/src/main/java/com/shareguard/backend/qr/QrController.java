package com.shareguard.backend.qr;

import com.shareguard.backend.common.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/qr")
public class QrController {
    private final QrTokenService service;

    public QrController(QrTokenService service) {
        this.service = service;
    }

    @PostMapping("/issue")
    QrDtos.IssueResponse issue(JwtAuthenticationToken authentication, @Valid @RequestBody QrDtos.IssueRequest request) {
        return service.issue(AuthenticatedUser.id(authentication), request);
    }

    @PostMapping("/verify")
    QrDtos.VerifyResponse verify(JwtAuthenticationToken authentication, @Valid @RequestBody QrDtos.VerifyRequest request) {
        return service.verify(AuthenticatedUser.id(authentication), request);
    }
}
