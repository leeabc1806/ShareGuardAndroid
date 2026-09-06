package com.shareguard.backend.qr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.shareguard.backend.common.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QrTokenCodecTest {
    @Test
    void roundTripPreservesSignedFieldsAndSecret() {
        var contractId = UUID.randomUUID();
        var expiresAt = Instant.ofEpochSecond(1_800_000_000L);
        var secret = new byte[32];
        for (int index = 0; index < secret.length; index++) secret[index] = (byte) index;

        var decoded = QrTokenCodec.decode(QrTokenCodec.encode(contractId, "start", expiresAt, secret));

        assertEquals(contractId, decoded.contractId());
        assertEquals("start", decoded.action());
        assertEquals(expiresAt, decoded.expiresAt());
        assertArrayEquals(secret, decoded.secret());
        assertEquals(64, QrTokenCodec.hash(secret).length());
    }

    @Test
    void malformedPayloadIsRejected() {
        assertThrows(ApiException.class, () -> QrTokenCodec.decode("SG2|broken"));
    }
}
