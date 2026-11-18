package dev.showroom.testdrive.booking;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import dev.showroom.testdrive.idempotency.IdempotencyService;
import dev.showroom.testdrive.idempotency.InMemoryIdempotencyStore;
import dev.showroom.testdrive.web.ConflictException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Web slice: controller + JSON + validation + problem details. The service is a mock. */
@WebMvcTest(BookingController.class)
@Import(BookingControllerTest.Idempotency.class)
class BookingControllerTest {

    @TestConfiguration
    static class Idempotency {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        IdempotencyService idempotencyService(JsonMapper json, MeterRegistry meters) {
            return new IdempotencyService(new InMemoryIdempotencyStore(Clock.systemUTC()), json, Duration.ofHours(1), meters);
        }
    }

    private static final String BODY = """
            {"carId":1,"customerName":"Asha Rao","phone":"9876543210","slotStart":"2026-11-20T11:00:00"}""";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    BookingService bookings;

    private BookingResponse booked() {
        return new BookingResponse(UUID.fromString("00000000-0000-0000-0000-000000000001"), 1, "Aurora EV", "Asha Rao",
                "******3210", null, LocalDateTime.parse("2026-11-20T11:00:00"), BookingStatus.CONFIRMED,
                Instant.parse("2026-11-18T05:00:00Z"));
    }

    @Test
    void bookingReturns201WithLocationAndARetryReplaysIt() throws Exception {
        given(bookings.book(any())).willReturn(booked());

        for (String replayed : new String[] {"false", "true"}) {
            mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", "slice-key-0001").content(BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/bookings/00000000-0000-0000-0000-000000000001"))
                    .andExpect(header().string("Idempotent-Replayed", replayed))
                    .andExpect(jsonPath("$.carModel").value("Aurora EV"))
                    .andExpect(jsonPath("$.status").value("CONFIRMED"));
        }
        verify(bookings, times(1)).book(any());
    }

    @Test
    void invalidFieldsAndHeaderComeBackAsOneProblem() throws Exception {
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "short")
                        .content("""
                                {"carId":1,"customerName":"","phone":"12345","slotStart":"2026-11-20T11:00:00"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors.phone").value("must be a 10-digit Indian mobile number"))
                .andExpect(jsonPath("$.errors.customerName").exists())
                .andExpect(jsonPath("$.errors.Idempotency-Key").exists());
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Required header 'Idempotency-Key' is not present."));
    }

    @Test
    void slotTakenIs409ProblemDetail() throws Exception {
        given(bookings.book(any())).willThrow(new ConflictException("slot-taken", "Aurora EV is already booked"));

        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "slice-key-0002").content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://showroom.example/problems/slot-taken"))
                .andExpect(jsonPath("$.code").value("slot-taken"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "slice-key-0003").content("{not json"))
                .andExpect(status().isBadRequest());
    }
}
