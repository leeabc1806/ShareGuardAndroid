package com.shareguard.backend.auth;

import com.shareguard.backend.common.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MfaVerificationRepository {
    private final JdbcTemplate jdbc;

    public MfaVerificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isVerified(UUID userId) {
        return Boolean.TRUE.equals(jdbc.query(
            "select verified from public.profiles where id = ?",
            rs -> rs.next() && rs.getBoolean("verified"),
            userId
        ));
    }

    public ProfileVerification lockProfile(UUID userId) {
        return jdbc.query(
            "select verified, trust_score, verified_at from public.profiles where id = ? for update",
            rs -> {
                if (!rs.next()) throw new ApiException(404, "사용자 프로필을 찾을 수 없습니다.");
                var timestamp = rs.getTimestamp("verified_at");
                return new ProfileVerification(
                    rs.getBoolean("verified"),
                    rs.getInt("trust_score"),
                    timestamp == null ? null : timestamp.toInstant()
                );
            },
            userId
        );
    }

    public Optional<UUID> userIdForIdentityHash(String identityHash) {
        return jdbc.query(
            "select user_id from private.mfa_verifications where identity_hash = ?",
            rs -> rs.next() ? Optional.of(rs.getObject("user_id", UUID.class)) : Optional.empty(),
            identityHash
        );
    }

    public void insertVerification(
        UUID userId,
        String verificationId,
        String identityHash,
        String phoneLast4,
        int trustScoreBefore,
        int trustScoreAfter,
        Instant verifiedAt
    ) {
        jdbc.update("""
            insert into private.mfa_verifications (
                user_id, portone_verification_id, identity_hash, phone_last4,
                trust_score_before, trust_score_after, verified_at
            ) values (?, ?, ?, ?, ?, ?, ?)
            """,
            userId, verificationId, identityHash, phoneLast4,
            trustScoreBefore, trustScoreAfter, Timestamp.from(verifiedAt)
        );
    }

    public void markVerified(UUID userId, int trustScore, Instant verifiedAt) {
        int changed = jdbc.update("""
            update public.profiles
            set verified = true,
                trust_score = ?,
                verified_at = ?,
                verification_provider = 'portone',
                updated_at = now()
            where id = ?
            """, trustScore, Timestamp.from(verifiedAt), userId);
        if (changed != 1) throw new ApiException(404, "사용자 프로필을 찾을 수 없습니다.");
    }

    public record ProfileVerification(boolean verified, int trustScore, Instant verifiedAt) {}
}
