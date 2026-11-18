package dev.showroom.testdrive.outbox;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The event contract on topic showroom.test-drive-bookings.v1 (JSON value, key = bookingId,
 * so all events of one booking stay in order on one partition). eventId lets consumers dedupe.
 */
public record BookingEvent(UUID eventId, String type, UUID bookingId, long carId, String carModel,
                           String customerName, LocalDateTime slotStart, Instant occurredAt) {

    public static final String BOOKED = "TestDriveBooked";
    public static final String CANCELLED = "TestDriveCancelled";

    public static BookingEvent of(String type, UUID bookingId, long carId, String carModel, String customerName,
                                  LocalDateTime slotStart, Instant occurredAt) {
        return new BookingEvent(UUID.randomUUID(), type, bookingId, carId, carModel, customerName, slotStart, occurredAt);
    }
}
