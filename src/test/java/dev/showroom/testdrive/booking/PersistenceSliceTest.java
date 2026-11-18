package dev.showroom.testdrive.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import dev.showroom.testdrive.catalog.Car;
import dev.showroom.testdrive.catalog.CarRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * JPA slice on H2 (PostgreSQL mode) with the real Flyway migrations: proves the schema, the seed
 * data and the constraint that stops double bookings. Each test rolls back.
 */
@DataJpaTest
@ActiveProfiles("local")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PersistenceSliceTest {

    private static final LocalDateTime SLOT = LocalDateTime.parse("2026-11-20T11:00:00");

    @Autowired CarRepository cars;
    @Autowired BookingRepository bookings;
    @Autowired SlotHoldRepository holds;

    private Booking newBooking(Car car, String name) {
        return bookings.save(Booking.confirm(car, name, "9876543210", null, SLOT, Instant.now()));
    }

    @Test
    void flywaySeedsFourActiveCarsAndHidesTheRetiredOne() {
        assertThat(cars.findByActiveTrueOrderByIdAsc()).extracting(Car::getModel)
                .containsExactly("Aurora EV", "Stratus 4x4", "Comet Hatch", "Vega Sedan");
        assertThat(cars.findById(5L)).get().extracting(Car::isActive).isEqualTo(false);
    }

    @Test
    void theSlotPrimaryKeyRejectsASecondHoldForTheSameCarAndTime() {
        Car car = cars.findById(1L).orElseThrow();
        Booking first = newBooking(car, "Asha");
        Booking second = newBooking(car, "Ravi");
        holds.saveAndFlush(new SlotHold(new SlotKey(1L, SLOT), first.getId()));

        assertThatThrownBy(() -> holds.saveAndFlush(new SlotHold(new SlotKey(1L, SLOT), second.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void takenSlotsAndReleaseWorkPerCar() {
        Car car = cars.findById(2L).orElseThrow();
        Booking booking = newBooking(car, "Asha");
        holds.saveAndFlush(new SlotHold(new SlotKey(2L, SLOT), booking.getId()));

        List<LocalDateTime> taken = holds.takenSlots(2L, SLOT.toLocalDate().atStartOfDay(), SLOT.plusDays(1));
        assertThat(taken).containsExactly(SLOT);
        assertThat(holds.takenSlots(1L, SLOT.toLocalDate().atStartOfDay(), SLOT.plusDays(1))).isEmpty();

        assertThat(holds.releaseFor(booking.getId())).isEqualTo(1);
        assertThat(holds.existsById(new SlotKey(2L, SLOT))).isFalse();
    }

    @Test
    void cancellingPersistsTheNewStatusThroughTheVersionedUpdate() {
        Booking booking = newBooking(cars.findById(3L).orElseThrow(), "Asha");
        bookings.flush();
        booking.cancel(Instant.now());
        bookings.saveAndFlush(booking);
        assertThat(bookings.findById(booking.getId())).get().extracting(Booking::getStatus).isEqualTo(BookingStatus.CANCELLED);
    }
}
