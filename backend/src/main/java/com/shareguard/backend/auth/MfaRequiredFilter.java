package com.shareguard.backend.auth;

import com.shareguard.backend.common.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
public class MfaRequiredFilter extends OncePerRequestFilter {
    private final MfaVerificationRepository repository;
    private final ObjectMapper objectMapper;

    public MfaRequiredFilter(MfaVerificationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;
        var path = request.getRequestURI();
        // ITEM-02 and every money-changing payment request require verified identity.
        return !("/api/v1/items".equals(path) || path.startsWith("/api/v1/payments/"));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            // The resource-server authentication entry point will produce the correct 401.
            filterChain.doFilter(request, response);
            return;
        }
        if (repository.isVerified(AuthenticatedUser.id(jwt))) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
            "code", "MFA_REQUIRED",
            "message", "PASS 휴대폰 본인인증 후 이용할 수 있습니다.",
            "timestamp", Instant.now().toString()
        ));
    }
}
