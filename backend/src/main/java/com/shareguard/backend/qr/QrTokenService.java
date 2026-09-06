package com.shareguard.backend.qr;

import com.shareguard.backend.common.ApiException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QrTokenService {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();
    private final long ttlSeconds;

    public QrTokenService(JdbcTemplate jdbc, @Value("${shareguard.qr.ttl-seconds}") long ttlSeconds) {
        this.jdbc = jdbc;
        this.ttlSeconds = ttlSeconds;
    }

    @Transactional
    public QrDtos.IssueResponse issue(UUID actorId, QrDtos.IssueRequest request) {
        var contract = contractForUpdate(request.contractId());
        requireParticipant(actorId, contract);
        requireTransitionReady(contract, request.action());

        var secret = new byte[32];
        random.nextBytes(secret);
        var expiresAt = Instant.now().plusSeconds(ttlSeconds);
        jdbc.update("""
            insert into private.qr_tokens(contract_id, action, token_hash, issued_by, expires_at)
            values (?, ?, ?, ?, ?)
            """, request.contractId(), request.action(), QrTokenCodec.hash(secret), actorId, Timestamp.from(expiresAt));
        return new QrDtos.IssueResponse(QrTokenCodec.encode(request.contractId(), request.action(), expiresAt, secret), expiresAt);
    }

    @Transactional
    public QrDtos.VerifyResponse verify(UUID actorId, QrDtos.VerifyRequest request) {
        var decoded = QrTokenCodec.decode(request.payload());
        if (decoded.expiresAt().isBefore(Instant.now())) throw new ApiException(410, "만료된 QR입니다.");

        TokenRow token;
        try {
            token = jdbc.queryForObject("""
                select contract_id, action, issued_by, expires_at, used_at
                  from private.qr_tokens
                 where token_hash = ?
                 for update
                """, (rs, ignored) -> new TokenRow(
                rs.getObject("contract_id", UUID.class), rs.getString("action"),
                rs.getObject("issued_by", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("used_at") == null ? null : rs.getTimestamp("used_at").toInstant()
            ), QrTokenCodec.hash(decoded.secret()));
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(404, "등록되지 않은 QR입니다.");
        }
        if (!token.contractId().equals(decoded.contractId()) || !token.action().equals(decoded.action())
            || !token.expiresAt().equals(decoded.expiresAt())) throw new ApiException(400, "QR 내용이 변조되었습니다.");
        if (token.usedAt() != null) throw new ApiException(409, "이미 사용된 QR입니다.");
        if (token.expiresAt().isBefore(Instant.now())) throw new ApiException(410, "만료된 QR입니다.");
        if (token.issuedBy().equals(actorId)) throw new ApiException(403, "QR 발급자는 자신의 QR을 인증할 수 없습니다.");

        var contract = contractForUpdate(token.contractId());
        requireParticipant(actorId, contract);
        requireTransitionReady(contract, token.action());
        var now = Instant.now();
        jdbc.update("update private.qr_tokens set used_at = ?, verified_by = ? where token_hash = ? and used_at is null",
            Timestamp.from(now), actorId, QrTokenCodec.hash(decoded.secret()));
        jdbc.update("""
            insert into public.qr_verifications(contract_id, verified_by, verification_type, latitude, longitude)
            values (?, ?, ?, ?, ?)
            """, token.contractId(), actorId, token.action(), request.latitude(), request.longitude());

        String nextStatus = token.action().equals("start") ? "active" : "completed";
        String reservationStatus = token.action().equals("start") ? "renting" : "completed";
        jdbc.update("update public.rental_contracts set status = ?, updated_at = now() where id = ?", nextStatus, token.contractId());
        jdbc.update("update public.reservations set status = ?, updated_at = now() where id = ?", reservationStatus, contract.reservationId());
        jdbc.update("""
            insert into private.audit_log(actor_id, action, target_type, target_id, details)
            values (?, ?, 'contract', ?, jsonb_build_object('qr_action', ?))
            """, actorId, "QR_" + token.action().toUpperCase() + "_VERIFIED", token.contractId().toString(), token.action());
        return new QrDtos.VerifyResponse(token.contractId(), token.action(), nextStatus, now);
    }

    private ContractRow contractForUpdate(UUID contractId) {
        try {
            return jdbc.queryForObject("""
                select c.id, c.reservation_id, c.status, r.borrower_id, i.owner_id,
                       exists(select 1 from public.condition_photos p where p.contract_id = c.id and p.phase = 'before') as has_before,
                       exists(select 1 from public.condition_photos p where p.contract_id = c.id and p.phase = 'after') as has_after
                  from public.rental_contracts c
                  join public.reservations r on r.id = c.reservation_id
                  join public.items i on i.id = r.item_id
                 where c.id = ?
                 for update of c, r
                """, (rs, ignored) -> new ContractRow(
                rs.getObject("id", UUID.class), rs.getObject("reservation_id", UUID.class), rs.getString("status"),
                rs.getObject("borrower_id", UUID.class), rs.getObject("owner_id", UUID.class),
                rs.getBoolean("has_before"), rs.getBoolean("has_after")
            ), contractId);
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(404, "계약을 찾을 수 없습니다.");
        }
    }

    private void requireParticipant(UUID actorId, ContractRow contract) {
        if (!actorId.equals(contract.borrowerId()) && !actorId.equals(contract.ownerId())) {
            throw new ApiException(403, "계약 참여자만 QR을 사용할 수 있습니다.");
        }
    }

    private void requireTransitionReady(ContractRow contract, String action) {
        if (action.equals("start") && (!contract.status().equals("signed") || !contract.hasBefore())) {
            throw new ApiException(409, "양측 서명과 대여 전 사진이 필요합니다.");
        }
        if (action.equals("end") && (!contract.status().equals("active") || !contract.hasAfter())) {
            throw new ApiException(409, "대여 중 상태와 반납 후 사진이 필요합니다.");
        }
    }

    private record TokenRow(UUID contractId, String action, UUID issuedBy, Instant expiresAt, Instant usedAt) {}
    private record ContractRow(
        UUID id, UUID reservationId, String status, UUID borrowerId, UUID ownerId, boolean hasBefore, boolean hasAfter
    ) {}
}
