package com.shareguard.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.shareguard.backend.common.ApiException;
import com.shareguard.backend.payment.PaymentTransactionService;
import com.shareguard.backend.qr.QrDtos;
import com.shareguard.backend.qr.QrTokenService;
import java.util.UUID;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Only runs against the explicitly named disposable loopback database, never a team database. */
@EnabledIfEnvironmentVariable(named = "SHAREGUARD_TEST_DB_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55432/shareguard")
class LocalDatabaseIntegrationTest {
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(System.getenv("SHAREGUARD_TEST_DB_URL"), "postgres",
            System.getenv("SHAREGUARD_TEST_DB_PASSWORD"));
        var flyway = Flyway.configure().dataSource(source).baselineOnMigrate(true).baselineVersion("0").load();
        flyway.migrate();
        flyway.validate();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    private void withTrade(Consumer<Trade> test) {
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            UUID owner = UUID.randomUUID(), borrower = UUID.randomUUID(), item = UUID.randomUUID();
            UUID reservation = UUID.randomUUID(), contract = UUID.randomUUID(), order = UUID.randomUUID();
            jdbc.update("insert into auth.users(id,email) values (?, 'owner@local.test'), (?, 'borrower@local.test')", owner, borrower);
            jdbc.update("update public.profiles set verified = true where id in (?, ?)", owner, borrower);
            jdbc.update("""
                insert into public.items(id,owner_id,title,category,daily_price,deposit,address)
                values (?, ?, 'Camera', 'camera', 2000, 10000, 'local test')
                """, item, owner);
            jdbc.update("""
                insert into public.reservations(id,item_id,borrower_id,start_date,end_date,rental_fee,deposit,status)
                values (?, ?, ?, current_date, current_date, 2000, 10000, 'paid')
                """, reservation, item, borrower);
            jdbc.update("""
                insert into public.rental_contracts(id,reservation_id,status,owner_signed_at,borrower_signed_at)
                values (?, ?, 'signed', now(), now())
                """, contract, reservation);
            jdbc.update("""
                insert into public.condition_photos(contract_id,uploader_id,phase,storage_path)
                values (?, ?, 'before', ?), (?, ?, 'after', ?)
                """, contract, owner, contract + "/before.jpg", contract, borrower, contract + "/after.jpg");
            jdbc.update("""
                insert into private.payment_orders(id,reservation_id,payer_id,order_id,payment_key,
                    expected_amount,approved_amount,status,idempotency_key)
                values (?, ?, ?, ?, ?, 12000, 12000, 'paid', ?)
                """, order, reservation, borrower, "shareguard-" + order, "key-" + order, UUID.randomUUID());
            jdbc.update("""
                insert into public.payments(reservation_id,payer_id,provider,amount,status)
                values (?, ?, 'toss', 12000, 'held')
                """, reservation, borrower);
            test.accept(new Trade(owner, borrower, reservation, contract, order));
        });
    }

    private void finish(Trade trade) {
        var qr = new QrTokenService(jdbc, 300);
        var start = qr.issue(trade.owner(), new QrDtos.IssueRequest(trade.contract(), "start"));
        qr.verify(trade.borrower(), new QrDtos.VerifyRequest(start.payload(), trade.contract(), "start", null, null));
        var end = qr.issue(trade.borrower(), new QrDtos.IssueRequest(trade.contract(), "end"));
        qr.verify(trade.owner(), new QrDtos.VerifyRequest(end.payload(), trade.contract(), "end", null, null));
    }

    @Test
    void migrationRevokesDemoWritesAndPreservesExplicitSigningRpc() {
        assertEquals(3, jdbc.queryForObject("select count(*) from public.flyway_schema_history where version in ('1','2','3') and success", Integer.class));
        assertFalse(jdbc.queryForObject("select has_function_privilege('authenticated', 'public.pay_demo_reservation(uuid)', 'EXECUTE')", Boolean.class));
        assertFalse(jdbc.queryForObject("select has_function_privilege('authenticated', 'public.verify_rental_transition(uuid,text)', 'EXECUTE')", Boolean.class));
        assertFalse(jdbc.queryForObject("select has_table_privilege('authenticated', 'public.payments', 'UPDATE')", Boolean.class));
        assertFalse(jdbc.queryForObject("select has_table_privilege('authenticated', 'public.rental_contracts', 'UPDATE')", Boolean.class));
        assertTrue(jdbc.queryForObject("select has_function_privilege('authenticated', 'public.sign_rental_contract(uuid)', 'EXECUTE')", Boolean.class));
    }

    @Test
    void flywayHistoryIsHiddenFromApiRolesButStillReadableByFlywayOwner() {
        assertTrue(jdbc.queryForObject("select relrowsecurity from pg_class where oid = 'public.flyway_schema_history'::regclass", Boolean.class));
        assertFalse(jdbc.queryForObject("select relforcerowsecurity from pg_class where oid = 'public.flyway_schema_history'::regclass", Boolean.class));
        for (String role : new String[] {"anon", "authenticated", "service_role"}) {
            assertFalse(jdbc.queryForObject(
                "select has_table_privilege(?, 'public.flyway_schema_history', 'SELECT')", Boolean.class, role));
        }
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from public.flyway_schema_history where version = '7' and success", Integer.class));
    }

    @Test
    void realDatabaseQrRoundTripRejectsReuse() {
        withTrade(trade -> {
            var qr = new QrTokenService(jdbc, 300);
            var issued = qr.issue(trade.owner(), new QrDtos.IssueRequest(trade.contract(), "start"));
            var verify = new QrDtos.VerifyRequest(issued.payload(), trade.contract(), "start", null, null);
            assertEquals("active", qr.verify(trade.borrower(), verify).contractStatus());
            assertThrows(ApiException.class, () -> qr.verify(trade.borrower(), verify));
            assertEquals(1, jdbc.queryForObject("select count(*) from public.qr_verifications where contract_id = ?", Integer.class, trade.contract()));
        });
    }

    @Test
    void returningUpdatesRealProfileDealsButDoesNotRefundMoney() {
        withTrade(trade -> {
            finish(trade);
            var payments = new PaymentTransactionService(jdbc);
            var state = payments.state(trade.borrower(), trade.reservation());
            assertEquals("paid", state.status());
            assertEquals(0, state.refundedAmount());
            assertTrue(state.refundAllowed());
            assertEquals(10000, state.refundableAmount());
            assertEquals(1, jdbc.queryForObject("select deal_count from public.profiles where id = ?", Integer.class, trade.borrower()));
            assertEquals(662, jdbc.queryForObject("select trust_score from public.profiles where id = ?", Integer.class, trade.owner()));
        });
    }

    @Test
    void refundCannotExceedDepositAndInFlightRequestBlocksAnotherRefund() {
        withTrade(trade -> {
            finish(trade);
            var payments = new PaymentTransactionService(jdbc);
            assertThrows(ApiException.class, () -> payments.prepareRefund(trade.borrower(), trade.reservation(), 10001));
            payments.prepareRefund(trade.borrower(), trade.reservation(), 10000);
            assertEquals("refund_pending", payments.state(trade.owner(), trade.reservation()).status());
            assertThrows(ApiException.class, () -> payments.prepareRefund(trade.borrower(), trade.reservation(), 10000));
        });
    }

    @Test
    void unresolvedDisputeBlocksRefundAndStrangerCannotReadPayment() {
        withTrade(trade -> {
            finish(trade);
            jdbc.update("""
                insert into public.reports(reservation_id,reporter_id,reported_user_id,report_type,reason)
                values (?, ?, ?, 'damage', 'Item damage needs checking')
                """, trade.reservation(), trade.borrower(), trade.owner());
            var payments = new PaymentTransactionService(jdbc);
            assertFalse(payments.state(trade.owner(), trade.reservation()).refundAllowed());
            assertThrows(ApiException.class, () -> payments.prepareRefund(trade.borrower(), trade.reservation(), 10000));
            assertThrows(ApiException.class, () -> payments.state(UUID.randomUUID(), trade.reservation()));
        });
    }

    @Test
    void authenticatedBorrowerCanReadHiddenPastItemWithoutRecursivePolicy() {
        withTrade(trade -> {
            jdbc.update("update public.items set status = 'hidden' where id = (select item_id from public.reservations where id = ?)", trade.reservation());
            jdbc.execute("set local role authenticated");
            jdbc.queryForObject("select set_config('request.jwt.claim.sub', ?, true)", String.class, trade.borrower().toString());
            assertEquals(1, jdbc.queryForObject("select count(*) from public.items where id = (select item_id from public.reservations where id = ?)", Integer.class, trade.reservation()));
            assertEquals(1, jdbc.queryForObject("select count(*) from public.rental_contracts where id = ?", Integer.class, trade.contract()));
        });
    }

    @Test
    void bothParticipantsCanStillSignAfterDirectUpdatePrivilegeIsRevoked() {
        withTrade(trade -> {
            jdbc.update("update public.rental_contracts set status = 'draft', owner_signed_at = null, borrower_signed_at = null where id = ?", trade.contract());
            jdbc.execute("set local role authenticated");
            jdbc.queryForObject("select set_config('request.jwt.claim.sub', ?, true)", String.class, trade.owner().toString());
            assertEquals("draft", jdbc.queryForObject("select (public.sign_rental_contract(?)).status", String.class, trade.contract()));
            jdbc.queryForObject("select set_config('request.jwt.claim.sub', ?, true)", String.class, trade.borrower().toString());
            assertEquals("signed", jdbc.queryForObject("select (public.sign_rental_contract(?)).status", String.class, trade.contract()));
        });
    }

    @Test
    void allResolvedDeductionsReduceTheRefundableDeposit() {
        withTrade(trade -> {
            finish(trade);
            for (int amount : new int[] {2000, 1000}) {
                UUID report = UUID.randomUUID();
                jdbc.update("""
                    insert into public.reports(id,reservation_id,reporter_id,reported_user_id,report_type,reason,status)
                    values (?, ?, ?, ?, 'damage', 'Resolved damage report', 'resolved')
                    """, report, trade.reservation(), trade.borrower(), trade.owner());
                jdbc.update("""
                    insert into public.admin_actions(report_id,admin_id,decision,deduction_amount,note,sanction)
                    values (?, ?, 'partial', ?, 'local test decision', 'none')
                    """, report, trade.owner(), amount);
            }
            var payments = new PaymentTransactionService(jdbc);
            assertEquals(7000, payments.state(trade.borrower(), trade.reservation()).refundableAmount());
            assertThrows(ApiException.class, () -> payments.prepareRefund(trade.borrower(), trade.reservation(), 8000));
            payments.prepareRefund(trade.borrower(), trade.reservation(), 7000);
        });
    }

    private record Trade(UUID owner, UUID borrower, UUID reservation, UUID contract, UUID order) {}
}
