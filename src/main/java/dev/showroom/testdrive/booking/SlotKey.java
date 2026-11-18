package dev.showroom.testdrive.booking;

import java.io.Serializable;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Natural key of a test-drive slot: one car, one start time. */
@Embeddable
public record SlotKey(@Column(name = "car_id") Long carId, @Column(name = "slot_start") LocalDateTime slotStart)
        implements Serializable {
}
