package dev.showroom.testdrive.booking;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/** API view of a booking. The phone number is masked: the response may end up in logs or caches. */
public record BookingResponse(UUID id, long carId, String carModel, String customerName, String phone, String email,
                              LocalDateTime slotStart, BookingStatus status, Instant createdAt) {

    static BookingResponse from(Booking b) {
        return new BookingResponse(b.getId(), b.getCar().getId(), b.getCar().getModel(), b.getCustomerName(),
                mask(b.getPhone()), b.getEmail(), b.getSlotStart(), b.getStatus(), b.getCreatedAt());
    }

    static String mask(String phone) {
        return phone.length() <= 4 ? "****" : "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }
}
