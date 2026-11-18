package dev.showroom.testdrive.outbox;

import java.time.Clock;
import java.util.List;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polling relay: reads unpublished outbox rows in id order, publishes each, marks it sent.
 * At-least-once: a crash after publish but before commit sends the row again,
 * so consumers dedupe by eventId. Stops at the first failure to keep per-booking order.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final BookingEventPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final MeterRegistry meters;

    public OutboxRelay(OutboxRepository outbox, BookingEventPublisher publisher, Clock clock,
                       @Value("${app.outbox.batch-size:50}") int batchSize, MeterRegistry meters) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.meters = meters;
        Gauge.builder("showroom.outbox.pending", outbox, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox rows not yet published")
                .register(meters);
    }

    /**
     * One relay pass; returns how many rows were published. Scheduled and transactional on the SAME
     * public method, called through the Spring proxy. (A first version called it from another method of
     * this class: self-invocation skipped the transaction, published_at was never saved, and every row
     * was re-sent each poll. The consumer dedupe absorbed it; the outbox.pending gauge exposed it.)
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    @Transactional
    public int relayBatch() {
        List<OutboxEvent> batch = outbox.findByPublishedAtIsNullOrderByIdAsc(Limit.of(batchSize));
        int sent = 0;
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event);
            } catch (Exception e) {
                event.markFailed();
                meters.counter("showroom.outbox.publish.failures").increment();
                log.warn("Publish failed for outbox event {} (attempt {}): {}", event.getEventId(), event.getAttempts(), e.toString());
                break;
            }
            event.markPublished(clock.instant());
            sent++;
        }
        if (sent > 0) {
            meters.counter("showroom.outbox.published").increment(sent);
            log.debug("Outbox relay published {} event(s)", sent);
        }
        return sent;
    }
}
