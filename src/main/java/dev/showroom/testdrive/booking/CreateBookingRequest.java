package dev.showroom.testdrive.booking;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/bookings. slotStart is showroom local time (app.showroom.zone),
 * for example "2026-11-20T11:30:00".
 */
public record CreateBookingRequest(
        @NotNull @Positive Long carId,
        @NotBlank @Size(max = 80) String customerName,
        @NotBlank @Pattern(regexp = "^[6-9]\\d{9}$", message = "must be a 10-digit Indian mobile number") String phone,
        @Email @Size(max = 120) String email,
        @NotNull LocalDateTime slotStart) {
}
