package dev.showroom.testdrive.outbox;

/**
 * Sends one outbox row to the message broker and returns only when the broker has it.
 * Kafka in production; an in-process hand-off in the 'local' profile.
 */
public interface BookingEventPublisher {

    void publish(OutboxEvent event) throws Exception;
}
