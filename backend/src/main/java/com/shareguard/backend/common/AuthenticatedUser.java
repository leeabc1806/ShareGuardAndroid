package com.shareguard.backend.common;

import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class AuthenticatedUser {
    private AuthenticatedUser() {}

    public static UUID id(JwtAuthenticationToken authentication) {
        try {
            return UUID.fromString(authentication.getToken().getSubject());
        } catch (RuntimeException exception) {
            throw new ApiException(401, "유효한 사용자 식별자가 없는 인증 토큰입니다.");
        }
    }
}
