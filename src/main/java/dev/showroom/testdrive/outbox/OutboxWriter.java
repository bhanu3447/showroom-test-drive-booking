package dev.showroom.testdrive.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an event row in the caller's transaction (MANDATORY: there must be one).
 * Nothing talks to Kafka here; the relay does that after commit.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final JsonMapper json;

    public OutboxWriter(OutboxRepository outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(BookingEvent event) {
        outbox.save(new OutboxEvent(event.eventId(), event.bookingId(), event.type(),
                json.writeValueAsString(event), event.occurredAt()));
    }
}
