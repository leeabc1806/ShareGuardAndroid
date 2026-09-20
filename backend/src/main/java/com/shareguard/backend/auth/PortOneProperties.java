package com.shareguard.backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shareguard.portone")
public record PortOneProperties(String baseUrl, String apiSecret, String storeId) {
    public PortOneProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.portone.io" : baseUrl;
        apiSecret = apiSecret == null ? "" : apiSecret;
        storeId = storeId == null ? "" : storeId;
    }
}
