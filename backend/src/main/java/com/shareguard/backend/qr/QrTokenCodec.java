package com.shareguard.backend.qr;

import com.shareguard.backend.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

final class QrTokenCodec {
    private static final String PREFIX = "SG2";

    private QrTokenCodec() {}

    static String encode(UUID contractId, String action, Instant expiresAt, byte[] secret) {
        return String.join("|", PREFIX, contractId.toString(), action,
            Long.toString(expiresAt.getEpochSecond()), Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
    }

    static Decoded decode(String payload) {
        try {
            var parts = payload.split("\\|", -1);
            if (parts.length != 5 || !PREFIX.equals(parts[0]) || !(parts[2].equals("start") || parts[2].equals("end"))) {
                throw new IllegalArgumentException();
            }
            var secret = Base64.getUrlDecoder().decode(parts[4]);
            if (secret.length != 32) throw new IllegalArgumentException();
            return new Decoded(UUID.fromString(parts[1]), parts[2], Instant.ofEpochSecond(Long.parseLong(parts[3])), secret);
        } catch (RuntimeException exception) {
            throw new ApiException(400, "올바르지 않은 ShareGuard QR입니다.");
        }
    }

    static String hash(byte[] secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    record Decoded(UUID contractId, String action, Instant expiresAt, byte[] secret) {}
}
