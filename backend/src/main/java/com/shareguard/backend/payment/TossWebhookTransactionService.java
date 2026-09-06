package com.shareguard.backend.payment;

import tools.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TossWebhookTransactionService {
    private final JdbcTemplate jdbc;

    public TossWebhookTransactionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean begin(String transmissionId, String eventType, JsonNode payload) {
        return jdbc.update("""
            insert into private.webhook_events(transmission_id, event_type, payload)
            values (?, ?, ?::jsonb)
            on conflict (transmission_id) do update
               set attempts = private.webhook_events.attempts + 1,
                   status = 'received', payload = excluded.payload, error_code = null, received_at = now()
             where private.webhook_events.status = 'failed'
                or (private.webhook_events.status = 'received' and private.webhook_events.received_at < now() - interval '5 minutes')
            """, transmissionId, eventType, payload.toString()) == 1;
    }

    @Transactional
    public void complete(String transmissionId) {
        jdbc.update("update private.webhook_events set status = 'processed', processed_at = now() where transmission_id = ?", transmissionId);
    }

    @Transactional
    public void fail(String transmissionId, RuntimeException exception) {
        jdbc.update("update private.webhook_events set status = 'failed', error_code = ? where transmission_id = ?",
            exception.getClass().getSimpleName(), transmissionId);
    }
}
