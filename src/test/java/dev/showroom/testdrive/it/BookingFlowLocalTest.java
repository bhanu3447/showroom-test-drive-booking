package dev.showroom.testdrive.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dev.showroom.testdrive.notification.BookingEventHandler;
import dev.showroom.testdrive.notification.NotificationRepository;
import dev.showroom.testdrive.outbox.BookingEvent;
import dev.showroom.testdrive.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * Full application on a real port, profile 'local' (H2 + in-memory bus + in-memory idempotency store).
 * Runs anywhere, no Docker. The same flow against PostgreSQL, Redis and Kafka is BookingFlowContainersIT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class BookingFlowLocalTest {

    private static final LocalDate TOMORROW = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);

    @LocalServerPort
    int port;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    BookingEventHandler handler;

    @Autowired
    NotificationRepository notifications;

    private HttpApi api() {
        return new HttpApi(port);
    }

    @Test
    void bookReplayNotifyCancelAndRebook() {
        HttpApi api = api();
        LocalDateTime slot = TOMORROW.atTime(11, 0);
        String key = HttpApi.newKey();

        HttpApi.Reply first = api.book(key, 1, "Asha Rao", slot);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.header("Idempotent-Replayed")).isEqualTo("false");
        String id = first.json().get("id").asString();

        HttpApi.Reply retry = api.book(key, 1, "Asha Rao", slot);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.json().get("id").asString()).isEqualTo(id);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode notes = api.get("/api/bookings/" + id + "/notifications").json();
            assertThat(notes).hasSize(1);
            assertThat(notes.get(0).get("message").asString()).contains("Aurora EV test drive is confirmed");
        });

        assertThat(slotAvailable(api, 1, slot)).isFalse();
        assertThat(api.post("/api/bookings/" + id + "/cancel").json().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(api.post("/api/bookings/" + id + "/cancel").status()).isEqualTo(200);   // cancel is idempotent
        assertThat(slotAvailable(api, 1, slot)).isTrue();

        assertThat(api.book(HttpApi.newKey(), 1, "Ravi", slot).status()).isEqualTo(201);  // freed slot can be rebooked

        // the relay marks rows as published (this caught a self-invocation bug: rows were re-sent forever)
        await().atMost(Duration.ofSeconds(10)).until(() -> outbox.countByPublishedAtIsNull() == 0);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(api.get("/api/bookings/" + id + "/notifications").json()).hasSize(2));
    }

    @Test
    void eightCustomersRaceForOneSlotAndExactlyOneWins() throws Exception {
        LocalDateTime slot = TOMORROW.atTime(15, 30);
        List<Callable<Integer>> racers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = "Racer " + i;
            racers.add(() -> api().book(HttpApi.newKey(), 2, name, slot).status());
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<Integer> f : pool.invokeAll(racers)) {
                statuses.add(f.get());
            }
        }
        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
    }

    @Test
    void aRedeliveredEventIsHandledOnlyOnce() {
        UUID booking = UUID.randomUUID();
        BookingEvent event = BookingEvent.of(BookingEvent.BOOKED, booking, 4, "Vega Sedan", "Asha",
                TOMORROW.atTime(12, 0), Instant.now());

        assertThat(handler.handle(event)).isTrue();
        assertThat(handler.handle(event)).isFalse();          // at-least-once delivery, exactly-once effect
        assertThat(notifications.findByBookingIdOrderByIdAsc(booking)).hasSize(1);
    }

    @Test
    void problemDetailsForUnknownCarBadSlotAndUnknownBooking() {
        HttpApi api = api();
        HttpApi.Reply noCar = api.get("/api/cars/99");
        assertThat(noCar.status()).isEqualTo(404);
        assertThat(noCar.header("Content-Type")).isEqualTo("application/problem+json");
        assertThat(noCar.json().get("code").asString()).isEqualTo("car-not-found");

        HttpApi.Reply evening = api.book(HttpApi.newKey(), 3, "Asha", TOMORROW.atTime(19, 0));
        assertThat(evening.status()).isEqualTo(422);
        assertThat(evening.json().get("code").asString()).isEqualTo("outside-opening-hours");

        assertThat(api.get("/api/bookings/00000000-0000-0000-0000-000000000000").status()).isEqualTo(404);
    }

    @Test
    void actuatorHealthMetricsAndOpenApiAreUp() {
        HttpApi api = api();
        assertThat(api.get("/actuator/health").json().get("status").asString()).isEqualTo("UP");
        assertThat(api.get("/actuator/health/readiness").status()).isEqualTo(200);
        assertThat(api.get("/actuator/metrics/showroom.outbox.pending").status()).isEqualTo(200);
        assertThat(api.get("/v3/api-docs").json().get("paths").has("/api/bookings")).isTrue();
    }

    private static boolean slotAvailable(HttpApi api, long carId, LocalDateTime slot) {
        for (JsonNode s : api.get("/api/cars/" + carId + "/slots?date=" + slot.toLocalDate()).json()) {
            if (LocalDateTime.parse(s.get("start").asString()).equals(slot)) {
                return s.get("available").asBoolean();
            }
        }
        throw new AssertionError("slot not listed: " + slot);
    }
}
