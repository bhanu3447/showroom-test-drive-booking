package dev.showroom.testdrive.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Notifications", description = "What the event consumer produced")
public class NotificationController {

    public record NotificationResponse(UUID eventId, String channel, String message, Instant createdAt) {
    }

    private final NotificationRepository notifications;

    public NotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/bookings/{id}/notifications")
    @Operation(summary = "Messages the consumer created for a booking (shows the event really flowed)")
    public List<NotificationResponse> forBooking(@PathVariable UUID id) {
        return notifications.findByBookingIdOrderByIdAsc(id).stream()
                .map(n -> new NotificationResponse(n.getEventId(), n.getChannel(), n.getMessage(), n.getCreatedAt()))
                .toList();
    }
}
