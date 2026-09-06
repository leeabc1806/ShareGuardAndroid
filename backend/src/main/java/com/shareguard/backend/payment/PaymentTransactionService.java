package com.shareguard.backend.payment;

import tools.jackson.databind.JsonNode;
import com.shareguard.backend.common.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentTransactionService {
    private final JdbcTemplate jdbc;

    public PaymentTransactionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public PreparedPayment prepareConfirmation(
        UUID actorId, UUID reservationId, String paymentKey, String orderId, int amount
    ) {
        ReservationPayment reservation;
        try {
            reservation = jdbc.queryForObject("""
            select id, borrower_id, total_amount, status
              from public.reservations
             where id = ?
             for update
            """, (rs, row) -> new ReservationPayment(
                rs.getObject("id", UUID.class), rs.getObject("borrower_id", UUID.class),
                rs.getInt("total_amount"), rs.getString("status")
            ), reservationId);
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(404, "예약을 찾을 수 없습니다.");
        }
        if (reservation == null || !reservation.borrowerId().equals(actorId)) {
            throw new ApiException(403, "해당 예약을 결제할 권한이 없습니다.");
        }
        if (reservation.totalAmount() != amount) throw new ApiException(400, "예약 금액과 결제 금액이 일치하지 않습니다.");
        if (!List.of("approved", "paid").contains(reservation.status())) throw new ApiException(409, "결제 가능한 예약 상태가 아닙니다.");

        var existing = findByReservation(reservationId);
        if (existing != null && existing.status().equals("paid")) {
            if (!existing.orderId().equals(orderId) || existing.expectedAmount() != amount) {
                throw new ApiException(409, "이미 다른 주문으로 승인된 예약입니다.");
            }
            return existing;
        }
        if (existing != null && existing.status().equals("confirming")) {
            throw new ApiException(409, "이미 결제 승인 처리 중입니다.");
        }
        if (existing != null && !existing.status().equals("failed")) {
            throw new ApiException(409, "현재 결제 상태에서는 다시 승인할 수 없습니다.");
        }

        boolean sameRetry = existing != null
            && existing.orderId().equals(orderId)
            && existing.paymentKey().equals(paymentKey)
            && existing.expectedAmount() == amount;
        UUID idempotencyKey = sameRetry ? existing.idempotencyKey() : UUID.randomUUID();
        UUID paymentOrderId = existing == null ? UUID.randomUUID() : existing.id();
        if (existing == null) {
            jdbc.update("""
                insert into private.payment_orders
                    (id, reservation_id, payer_id, order_id, payment_key, expected_amount, status, idempotency_key)
                values (?, ?, ?, ?, ?, ?, 'confirming', ?)
                """, paymentOrderId, reservationId, actorId, orderId, paymentKey, amount, idempotencyKey);
        } else {
            jdbc.update("""
                update private.payment_orders
                   set order_id = ?, payment_key = ?, expected_amount = ?, idempotency_key = ?, status = 'confirming',
                       error_code = null, updated_at = now(), version = version + 1
                 where id = ? and status = 'failed'
                """, orderId, paymentKey, amount, idempotencyKey, paymentOrderId);
        }
        return new PreparedPayment(paymentOrderId, reservationId, actorId, orderId, paymentKey, amount, 0, "confirming", idempotencyKey);
    }

    @Transactional
    public PaymentDtos.PaymentResponse completeConfirmation(PreparedPayment order, JsonNode providerPayload) {
        int updated = jdbc.update("""
            update private.payment_orders
               set status = 'paid', approved_amount = expected_amount, provider_payload = ?::jsonb,
                   paid_at = now(), updated_at = now(), version = version + 1
             where id = ? and status in ('confirming', 'failed')
            """, providerPayload.toString(), order.id());
        if (updated != 1) throw new ApiException(409, "결제 상태가 이미 변경되었습니다.");
        jdbc.update("""
            insert into public.payments
                (reservation_id, payer_id, provider, provider_payment_id, amount, status, paid_at)
            values (?, ?, 'toss', ?, ?, 'held', now())
            on conflict (reservation_id) do update set
                provider = excluded.provider,
                provider_payment_id = excluded.provider_payment_id,
                amount = excluded.amount,
                status = 'held', paid_at = now(), updated_at = now()
            """, order.reservationId(), order.payerId(), order.paymentKey(), order.expectedAmount());
        jdbc.update("update public.reservations set status = 'paid', updated_at = now() where id = ?", order.reservationId());
        addEvent(order.id(), "PAYMENT_CONFIRMED", providerPayload.toString());
        addAudit(order.payerId(), "PAYMENT_CONFIRMED", "reservation", order.reservationId().toString(), providerPayload.toString());
        return response(order, "paid", order.expectedAmount(), 0);
    }

    public PaymentDtos.PaymentResponse responseFor(PreparedPayment order) {
        return response(order, order.status(), order.status().equals("paid") ? order.expectedAmount() : 0, order.refundedAmount());
    }

    @Transactional(readOnly = true)
    public PreparedPayment findByPaymentKey(String paymentKey) {
        try {
            return jdbc.queryForObject("select * from private.payment_orders where payment_key = ?", this::mapPrepared, paymentKey);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    @Transactional
    public void reconcileVerifiedProviderState(JsonNode providerPayload) {
        String paymentKey = providerPayload.path("paymentKey").asText();
        PreparedPayment order;
        try {
            order = jdbc.queryForObject(
                "select * from private.payment_orders where payment_key = ? for update", this::mapPrepared, paymentKey);
        } catch (EmptyResultDataAccessException ignored) {
            return;
        }
        String providerStatus = providerPayload.path("status").asText();
        if (providerStatus.equals("DONE") && (order.status().equals("confirming") || order.status().equals("failed"))) {
            completeConfirmation(order, providerPayload);
            return;
        }
        if (!providerStatus.equals("CANCELED") && !providerStatus.equals("PARTIAL_CANCELED")) return;
        int canceled = 0;
        for (JsonNode cancel : providerPayload.path("cancels")) canceled += cancel.path("cancelAmount").asInt(0);
        canceled = Math.min(canceled, order.expectedAmount());
        Integer deposit = jdbc.queryForObject("select deposit from public.reservations where id = ?", Integer.class, order.reservationId());
        String status = canceled >= (deposit == null ? order.expectedAmount() : deposit) ? "refunded" : "partially_refunded";
        jdbc.update("""
            update private.payment_orders
               set status = ?, refunded_amount = ?, provider_payload = ?::jsonb,
                   refunded_at = now(), updated_at = now(), version = version + 1
             where id = ?
            """, status, canceled, providerPayload.toString(), order.id());
        jdbc.update("update public.payments set status = ?, refunded_at = now(), updated_at = now() where reservation_id = ?",
            status, order.reservationId());
        addEvent(order.id(), "PAYMENT_WEBHOOK_RECONCILED", providerPayload.toString());
    }

    @Transactional
    public void failConfirmation(PreparedPayment order, RuntimeException error) {
        jdbc.update("""
            update private.payment_orders set status = 'failed', error_code = ?, updated_at = now(), version = version + 1
             where id = ? and status = 'confirming'
            """, error.getClass().getSimpleName(), order.id());
        addEvent(order.id(), "PAYMENT_CONFIRM_FAILED", "{\"message\":\"payment provider error\"}");
    }

    @Transactional
    public PreparedRefund prepareRefund(UUID actorId, UUID reservationId, int amount) {
        RefundRow result;
        try {
            result = jdbc.queryForObject("""
            select po.id, po.reservation_id, po.payer_id, po.order_id, po.payment_key,
                   po.approved_amount, po.refunded_amount, po.status, r.deposit,
                   (r.borrower_id = ? or i.owner_id = ? or p.role = 'admin') as allowed,
                   c.status as contract_status
              from private.payment_orders po
              join public.reservations r on r.id = po.reservation_id
              join public.items i on i.id = r.item_id
              join public.rental_contracts c on c.reservation_id = r.id
              join public.profiles p on p.id = ?
             where po.reservation_id = ?
             for update of po
            """, (rs, ignored) -> new RefundRow(new PreparedRefund(
                rs.getObject("id", UUID.class), rs.getObject("reservation_id", UUID.class),
                rs.getObject("payer_id", UUID.class), rs.getString("order_id"), rs.getString("payment_key"),
                rs.getInt("approved_amount"), rs.getInt("refunded_amount"), rs.getInt("deposit"), UUID.randomUUID()
            ), rs.getBoolean("allowed"), rs.getString("contract_status")), actorId, actorId, actorId, reservationId);
        } catch (EmptyResultDataAccessException exception) {
            throw new ApiException(404, "결제 주문을 찾을 수 없습니다.");
        }
        var row = result.refund();
        if (!result.allowed()) throw new ApiException(403, "환급 요청 권한이 없습니다.");
        if (!List.of("completed", "disputed").contains(result.contractStatus())) throw new ApiException(409, "반납 완료 또는 분쟁 상태에서만 환급할 수 있습니다.");
        int refundable = row.depositAmount() - row.refundedAmount();
        if (amount > refundable) throw new ApiException(400, "환급액이 남은 보증금보다 큽니다.");
        int updated = jdbc.update("""
            update private.payment_orders set status = 'refund_pending', updated_at = now(), version = version + 1
             where id = ? and status in ('paid', 'partially_refunded')
            """, row.id());
        if (updated != 1) throw new ApiException(409, "환급 가능한 결제 상태가 아닙니다.");
        return row;
    }

    @Transactional
    public PaymentDtos.PaymentResponse completeRefund(PreparedRefund refund, int amount, JsonNode payload, UUID actorId) {
        int totalRefunded = refund.refundedAmount() + amount;
        String status = totalRefunded >= refund.depositAmount() ? "refunded" : "partially_refunded";
        jdbc.update("""
            update private.payment_orders set status = ?, refunded_amount = ?, provider_payload = ?::jsonb,
                   refunded_at = now(), updated_at = now(), version = version + 1
             where id = ? and status = 'refund_pending'
            """, status, totalRefunded, payload.toString(), refund.id());
        jdbc.update("""
            update public.payments set status = ?, refunded_at = now(), updated_at = now()
             where reservation_id = ?
            """, status, refund.reservationId());
        addEvent(refund.id(), "PAYMENT_REFUNDED", payload.toString());
        addAudit(actorId, "PAYMENT_REFUNDED", "reservation", refund.reservationId().toString(), payload.toString());
        return new PaymentDtos.PaymentResponse(
            refund.id(), refund.reservationId(), refund.orderId(), status,
            refund.approvedAmount(), totalRefunded
        );
    }

    @Transactional
    public void failRefund(PreparedRefund refund, RuntimeException error) {
        jdbc.update("""
            update private.payment_orders set status = case when refunded_amount > 0 then 'partially_refunded' else 'paid' end,
                   error_code = ?, updated_at = now(), version = version + 1
             where id = ? and status = 'refund_pending'
            """, error.getClass().getSimpleName(), refund.id());
        addEvent(refund.id(), "PAYMENT_REFUND_FAILED", "{\"message\":\"payment provider error\"}");
    }

    private PreparedPayment findByReservation(UUID reservationId) {
        try {
            return jdbc.queryForObject("select * from private.payment_orders where reservation_id = ?", this::mapPrepared, reservationId);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    private PreparedPayment mapPrepared(ResultSet rs, int ignored) throws SQLException {
        return new PreparedPayment(
            rs.getObject("id", UUID.class), rs.getObject("reservation_id", UUID.class),
            rs.getObject("payer_id", UUID.class), rs.getString("order_id"), rs.getString("payment_key"),
            rs.getInt("expected_amount"), rs.getInt("refunded_amount"), rs.getString("status"),
            rs.getObject("idempotency_key", UUID.class)
        );
    }

    private PaymentDtos.PaymentResponse response(PreparedPayment order, String status, int approved, int refunded) {
        return new PaymentDtos.PaymentResponse(order.id(), order.reservationId(), order.orderId(), status, approved, refunded);
    }

    private void addEvent(UUID orderId, String type, String payload) {
        jdbc.update("insert into private.payment_events(payment_order_id, event_type, payload) values (?, ?, ?::jsonb)", orderId, type, payload);
    }

    private void addAudit(UUID actorId, String action, String targetType, String targetId, String details) {
        jdbc.update("insert into private.audit_log(actor_id, action, target_type, target_id, details) values (?, ?, ?, ?, ?::jsonb)",
            actorId, action, targetType, targetId, details);
    }

    private record ReservationPayment(UUID id, UUID borrowerId, int totalAmount, String status) {}
    private record RefundRow(PreparedRefund refund, boolean allowed, String contractStatus) {}
    public record PreparedPayment(
        UUID id, UUID reservationId, UUID payerId, String orderId, String paymentKey,
        int expectedAmount, int refundedAmount, String status, UUID idempotencyKey
    ) {}
    public record PreparedRefund(
        UUID id, UUID reservationId, UUID payerId, String orderId, String paymentKey,
        int approvedAmount, int refundedAmount, int depositAmount, UUID idempotencyKey
    ) {}
}
