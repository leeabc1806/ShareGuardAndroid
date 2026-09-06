package com.shareguard.backend.admin;

import com.shareguard.backend.common.ApiException;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDisputeService {
    private final JdbcTemplate jdbc;

    public AdminDisputeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public AdminDtos.ResolveReportResponse resolve(UUID adminId, UUID reportId, AdminDtos.ResolveReportRequest request) {
        requireAdmin(adminId);
        ReportRow report;
        try {
            report = jdbc.queryForObject("""
                select rp.id, rp.reservation_id, rp.reported_user_id, rp.status,
                       r.deposit, r.item_id
                  from public.reports rp
                  join public.reservations r on r.id = rp.reservation_id
                 where rp.id = ?
                 for update of rp, r
                """, (rs, ignored) -> new ReportRow(
                rs.getObject("id", UUID.class), rs.getObject("reservation_id", UUID.class),
                rs.getObject("reported_user_id", UUID.class), rs.getString("status"),
                rs.getInt("deposit"), rs.getObject("item_id", UUID.class)
            ), reportId);
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(404, "신고를 찾을 수 없습니다.");
        }
        if (!report.status().equals("open") && !report.status().equals("reviewing")) {
            throw new ApiException(409, "이미 처리된 신고입니다.");
        }
        if (request.deductionAmount() > report.deposit()) {
            throw new ApiException(400, "차감액은 보증금을 초과할 수 없습니다.");
        }
        if (request.decision().equals("dismissed") && request.deductionAmount() != 0) {
            throw new ApiException(400, "기각 결정에는 차감액을 설정할 수 없습니다.");
        }

        String status = request.decision().equals("dismissed") ? "dismissed" : "resolved";
        jdbc.update("""
            update public.reports set status = ?, resolution = ?, resolved_at = now() where id = ?
            """, status, request.note().trim(), reportId);
        jdbc.update("""
            insert into public.admin_actions(report_id, admin_id, decision, deduction_amount, sanction, note)
            values (?, ?, ?, ?, ?, ?)
            """, reportId, adminId, request.decision(), request.deductionAmount(), request.sanction(), request.note().trim());
        jdbc.update("update public.rental_contracts set status = 'completed', updated_at = now() where reservation_id = ?", report.reservationId());
        jdbc.update("update public.reservations set status = 'completed', updated_at = now() where id = ?", report.reservationId());
        applySanction(report, request.sanction());
        jdbc.update("""
            insert into private.audit_log(actor_id, action, target_type, target_id, details)
            values (?, 'ADMIN_REPORT_RESOLVED', 'report', ?,
                    jsonb_build_object('decision', ?, 'deduction_amount', ?, 'sanction', ?))
            """, adminId, reportId.toString(), request.decision(), request.deductionAmount(), request.sanction());

        int refundable = report.deposit() - request.deductionAmount();
        return new AdminDtos.ResolveReportResponse(reportId, report.reservationId(), status, refundable, refundable > 0);
    }

    private void requireAdmin(UUID actorId) {
        try {
            var role = jdbc.queryForObject("select role from public.profiles where id = ?", String.class, actorId);
            if (!"admin".equals(role)) throw new ApiException(403, "관리자 권한이 필요합니다.");
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(403, "관리자 권한이 필요합니다.");
        }
    }

    private void applySanction(ReportRow report, String sanction) {
        switch (sanction) {
            case "warning" -> jdbc.update("update public.profiles set account_status = 'warned' where id = ?", report.reportedUserId());
            case "hide_item" -> jdbc.update("update public.items set status = 'hidden', updated_at = now() where id = ?", report.itemId());
            case "suspend_user" -> jdbc.update("update public.profiles set account_status = 'suspended' where id = ?", report.reportedUserId());
            default -> { }
        }
    }

    private record ReportRow(
        UUID id, UUID reservationId, UUID reportedUserId, String status, int deposit, UUID itemId
    ) {}
}
