package dev.showroom.testdrive.notification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The SMS the sales advisor's system would send; stored so the consumer's work is visible and testable. */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id")
    private UUID eventId;
    @Column(name = "booking_id")
    private UUID bookingId;
    private String channel;
    private String message;
    @Column(name = "created_at")
    private Instant createdAt;

    protected Notification() {
    }

    Notification(UUID eventId, UUID bookingId, String channel, String message, Instant createdAt) {
        this.eventId = eventId;
        this.bookingId = bookingId;
        this.channel = channel;
        this.message = message;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getBookingId() { return bookingId; }
    public String getChannel() { return channel; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
