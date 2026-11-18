package dev.showroom.testdrive.notification;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import dev.showroom.testdrive.outbox.BookingEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consumer side ("notification service"): turns a booking event into an SMS for the customer.
 * Idempotent: the event id is a unique key, so a redelivered event is skipped, not sent twice.
 * The same handler runs behind the Kafka listener and behind the in-memory bus.
 */
@Component
public class BookingEventHandler {

    private static final Logger log = LoggerFactory.getLogger(BookingEventHandler.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);

    private final NotificationRepository notifications;
    private final Clock clock;
    private final MeterRegistry meters;

    public BookingEventHandler(NotificationRepository notifications, Clock clock, MeterRegistry meters) {
        this.notifications = notifications;
        this.clock = clock;
        this.meters = meters;
    }

    /** Returns false when the event was a duplicate. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean handle(BookingEvent event) {
        if (notifications.existsByEventId(event.eventId())) {
            return duplicate(event);
        }
        try {
            notifications.saveAndFlush(new Notification(event.eventId(), event.bookingId(), "SMS", message(event), clock.instant()));
        } catch (DataIntegrityViolationException raceWithRedelivery) {
            return duplicate(event);
        }
        meters.counter("showroom.events.consumed", "type", event.type()).increment();
        log.info("Notification queued: booking={} event={} type={}", event.bookingId(), event.eventId(), event.type());
        return true;
    }

    private boolean duplicate(BookingEvent event) {
        meters.counter("showroom.events.duplicates").increment();
        log.info("Duplicate event skipped: {}", event.eventId());
        return false;
    }

    static String message(BookingEvent e) {
        String when = e.slotStart().format(WHEN);
        return switch (e.type()) {
            case BookingEvent.BOOKED -> "Hi " + e.customerName() + ", your " + e.carModel() + " test drive is confirmed for "
                    + when + ". Bring your driving licence.";
            case BookingEvent.CANCELLED -> "Hi " + e.customerName() + ", your " + e.carModel() + " test drive on " + when
                    + " is cancelled.";
            default -> "Update on your test drive: " + e.type();
        };
    }
}
