package dev.showroom.testdrive.booking;

import java.time.LocalDateTime;

public record SlotResponse(LocalDateTime start, boolean available) {
}
