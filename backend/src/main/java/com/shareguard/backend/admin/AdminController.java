package com.shareguard.backend.admin;

import com.shareguard.backend.common.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminDisputeService service;

    public AdminController(AdminDisputeService service) {
        this.service = service;
    }

    @PostMapping("/reports/{reportId}/resolve")
    AdminDtos.ResolveReportResponse resolve(
        JwtAuthenticationToken authentication,
        @PathVariable UUID reportId,
        @Valid @RequestBody AdminDtos.ResolveReportRequest request
    ) {
        return service.resolve(AuthenticatedUser.id(authentication), reportId, request);
    }
}
