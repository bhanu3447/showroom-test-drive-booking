package dev.showroom.testdrive.booking;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

/**
 * A taken slot. Always INSERTed, never merged (isNew() = true): with an assigned id,
 * save() would otherwise MERGE and silently move an existing hold to another booking.
 */
@Entity
@Table(name = "slot_holds")
public class SlotHold implements Persistable<SlotKey> {

    @EmbeddedId
    private SlotKey id;

    @Column(name = "booking_id")
    private UUID bookingId;

    protected SlotHold() {
    }

    SlotHold(SlotKey id, UUID bookingId) {
        this.id = id;
        this.bookingId = bookingId;
    }

    @Override
    public SlotKey getId() { return id; }

    public UUID getBookingId() { return bookingId; }

    @Override
    public boolean isNew() { return true; }
}
