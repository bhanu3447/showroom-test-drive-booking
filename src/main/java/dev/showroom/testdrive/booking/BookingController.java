package dev.showroom.testdrive.booking;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import dev.showroom.testdrive.idempotency.IdempotencyService;
import dev.showroom.testdrive.idempotency.IdempotencyService.Outcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Test drives", description = "Free slots, booking and cancelling")
public class BookingController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String REPLAYED = "Idempotent-Replayed";

    private final BookingService bookings;
    private final IdempotencyService idempotency;

    public BookingController(BookingService bookings, IdempotencyService idempotency) {
        this.bookings = bookings;
        this.idempotency = idempotency;
    }

    @GetMapping("/api/cars/{carId}/slots")
    @Operation(summary = "Test-drive slots for one car on one day (showroom local time)")
    public List<SlotResponse> slots(@PathVariable long carId,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return bookings.slots(carId, date);
    }

    @PostMapping("/api/bookings")
    @Operation(summary = "Book a test drive. Send a unique Idempotency-Key; retries with the same key replay the first answer")
    public ResponseEntity<BookingResponse> book(
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = "[A-Za-z0-9_-]{8,64}",
                    message = "must be 8-64 characters: letters, digits, '-' or '_'") String key,
            @Valid @RequestBody CreateBookingRequest request) {
        Outcome<BookingResponse> outcome = idempotency.execute("booking:" + key, request, BookingResponse.class,
                () -> bookings.book(request));
        BookingResponse body = outcome.response();
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/bookings/" + body.id()))
                .header(REPLAYED, Boolean.toString(outcome.replayed()))
                .body(body);
    }

    @GetMapping("/api/bookings/{id}")
    public BookingResponse get(@PathVariable UUID id) {
        return bookings.get(id);
    }

    @PostMapping("/api/bookings/{id}/cancel")
    @Operation(summary = "Cancel a booking (safe to repeat) and free its slot")
    public BookingResponse cancel(@PathVariable UUID id) {
        return bookings.cancel(id);
    }
}
