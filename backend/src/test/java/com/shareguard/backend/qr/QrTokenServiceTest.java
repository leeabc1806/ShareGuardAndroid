package com.shareguard.backend.qr;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.shareguard.backend.common.ApiException;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class QrTokenServiceTest {
    private final UUID contract = UUID.randomUUID();
    private final UUID reservation = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID borrower = UUID.randomUUID();
    private final Instant expires = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS);
    private JdbcTemplate jdbc;
    private QrTokenService service;
    private ResultSet tokenRow;
    private ResultSet contractRow;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setup() throws Exception {
        jdbc = mock(JdbcTemplate.class);
        service = new QrTokenService(jdbc, 300);
        tokenRow = mock(ResultSet.class);
        contractRow = mock(ResultSet.class);
        when(tokenRow.getObject("contract_id", UUID.class)).thenReturn(contract);
        when(tokenRow.getObject("issued_by", UUID.class)).thenReturn(owner);
        when(tokenRow.getString("action")).thenReturn("start");
        when(tokenRow.getTimestamp("expires_at")).thenReturn(Timestamp.from(expires));
        when(contractRow.getObject("id", UUID.class)).thenReturn(contract);
        when(contractRow.getObject("reservation_id", UUID.class)).thenReturn(reservation);
        when(contractRow.getObject("borrower_id", UUID.class)).thenReturn(borrower);
        when(contractRow.getObject("owner_id", UUID.class)).thenReturn(owner);
        when(contractRow.getString("status")).thenReturn("signed");
        when(contractRow.getBoolean("has_before")).thenReturn(true);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object.class))).thenAnswer(call -> {
            RowMapper mapper = call.getArgument(1);
            return mapper.mapRow(call.<String>getArgument(0).contains("from private.qr_tokens") ? tokenRow : contractRow, 0);
        });
    }

    private QrDtos.VerifyRequest request(UUID selectedContract, String action) {
        String payload = QrTokenCodec.encode(contract, "start", expires, new byte[32]);
        return new QrDtos.VerifyRequest(payload, selectedContract, action, null, null);
    }

    @Test
    void issuedExpiryExactlyMatchesPersistedAndEncodedPrecision() {
        var issued = service.issue(owner, new QrDtos.IssueRequest(contract, "start"));
        var timestamp = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbc).update(contains("insert into private.qr_tokens"), eq(contract), eq("start"), anyString(), eq(owner), timestamp.capture());
        assertEquals(timestamp.getValue().toInstant(), QrTokenCodec.decode(issued.payload()).expiresAt());
        assertEquals(issued.expiresAt(), timestamp.getValue().toInstant());
    }

    @Test
    void rejectsWrongSelectedContractBeforeConsumingToken() {
        assertThrows(ApiException.class, () -> service.verify(borrower, request(UUID.randomUUID(), "start")));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsWrongSelectedActionBeforeConsumingToken() {
        assertThrows(ApiException.class, () -> service.verify(borrower, request(contract, "end")));
        verifyNoInteractions(jdbc);
    }

    @Test
    void issuerCannotScanOwnQr() {
        assertThrows(ApiException.class, () -> service.verify(owner, request(contract, "start")));
        verify(jdbc, never()).update(contains("set used_at"), any(), any(), any());
    }

    @Test
    void usedQrCannotChangeTransactionAgain() throws Exception {
        when(tokenRow.getTimestamp("used_at")).thenReturn(Timestamp.from(Instant.now()));
        assertThrows(ApiException.class, () -> service.verify(borrower, request(contract, "start")));
        verify(jdbc, never()).update(contains("set used_at"), any(), any(), any());
    }

    @Test
    void otherParticipantStartsRentalAndConsumesToken() {
        var result = service.verify(borrower, request(contract, "start"));
        assertEquals("active", result.contractStatus());
        verify(jdbc).update(contains("set used_at"), any(Timestamp.class), eq(borrower), anyString());
        verify(jdbc).update(contains("update public.reservations"), eq("renting"), eq(reservation));
    }

    @Test
    void strangerCannotVerifyValidQr() {
        assertThrows(ApiException.class, () -> service.verify(UUID.randomUUID(), request(contract, "start")));
        verify(jdbc, never()).update(contains("set used_at"), any(), any(), any());
    }
}
