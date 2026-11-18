package dev.showroom.testdrive.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.showroom.testdrive.catalog.Car;
import dev.showroom.testdrive.catalog.CarService;
import dev.showroom.testdrive.outbox.BookingEvent;
import dev.showroom.testdrive.outbox.OutboxWriter;
import dev.showroom.testdrive.web.ConflictException;
import dev.showroom.testdrive.web.NotFoundException;
import dev.showroom.testdrive.web.UnprocessableException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking rules. One transaction writes the booking, the slot hold and the outbox event,
 * so either all three exist or none do (no "booked but never announced").
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookings;
    private final SlotHoldRepository holds;
    private final CarService cars;
    private final SlotPolicy policy;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final MeterRegistry meters;

    public BookingService(BookingRepository bookings, SlotHoldRepository holds, CarService cars, SlotPolicy policy,
                          OutboxWriter outbox, Clock clock, MeterRegistry meters) {
        this.bookings = bookings;
        this.holds = holds;
        this.cars = cars;
        this.policy = policy;
        this.outbox = outbox;
        this.clock = clock;
        this.meters = meters;
    }

    @Transactional
    public BookingResponse book(CreateBookingRequest req) {
        Car car = cars.activeCar(req.carId());
        policy.check(req.slotStart());
        SlotKey slot = new SlotKey(car.getId(), req.slotStart());
        if (holds.existsById(slot)) {
            throw slotTaken(car, req.slotStart(), "pre-check");
        }
        Instant now = clock.instant();
        Booking booking = Booking.confirm(car, req.customerName().strip(), req.phone(), req.email(), req.slotStart(), now);
        bookings.save(booking);
        try {
            holds.saveAndFlush(new SlotHold(slot, booking.getId()));   // the real guard: primary key (car_id, slot_start)
        } catch (DataIntegrityViolationException race) {
            throw slotTaken(car, req.slotStart(), "race");
        }
        outbox.append(BookingEvent.of(BookingEvent.BOOKED, booking.getId(), car.getId(), car.getModel(),
                booking.getCustomerName(), booking.getSlotStart(), now));
        meters.counter("showroom.bookings.confirmed").increment();
        log.info("Test drive booked: booking={} car={} slot={}", booking.getId(), car.getId(), booking.getSlotStart());
        return BookingResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public BookingResponse get(UUID id) {
        return BookingResponse.from(find(id));
    }

    /** Cancelling twice is fine (idempotent): the second call returns the cancelled booking. */
    @Transactional
    public BookingResponse cancel(UUID id) {
        Booking booking = find(id);
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return BookingResponse.from(booking);
        }
        if (!policy.isFuture(booking.getSlotStart())) {
            throw new UnprocessableException("already-started", "A test drive that has started cannot be cancelled");
        }
        Instant now = clock.instant();
        booking.cancel(now);
        holds.releaseFor(id);
        Car car = booking.getCar();
        outbox.append(BookingEvent.of(BookingEvent.CANCELLED, id, car.getId(), car.getModel(),
                booking.getCustomerName(), booking.getSlotStart(), now));
        meters.counter("showroom.bookings.cancelled").increment();
        log.info("Test drive cancelled: booking={}", id);
        return BookingResponse.from(booking);
    }

    @Transactional(readOnly = true)
    public List<SlotResponse> slots(long carId, LocalDate date) {
        Car car = cars.activeCar(carId);
        policy.checkDate(date);
        Set<LocalDateTime> taken = new HashSet<>(
                holds.takenSlots(car.getId(), date.atStartOfDay(), date.plusDays(1).atStartOfDay()));
        return policy.slotsOn(date).stream()
                .map(start -> new SlotResponse(start, !taken.contains(start) && policy.isFuture(start)))
                .toList();
    }

    private Booking find(UUID id) {
        return bookings.findById(id).orElseThrow(() -> new NotFoundException("booking-not-found", "No booking " + id));
    }

    private ConflictException slotTaken(Car car, LocalDateTime start, String how) {
        meters.counter("showroom.bookings.rejected", "reason", "slot-taken", "detected", how).increment();
        return new ConflictException("slot-taken", car.getModel() + " is already booked at " + start);
    }
}
