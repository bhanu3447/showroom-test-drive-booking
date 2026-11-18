package dev.showroom.testdrive.booking;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import dev.showroom.testdrive.config.ShowroomProperties;
import dev.showroom.testdrive.web.UnprocessableException;
import org.springframework.stereotype.Component;

/** The showroom rules for when a test drive can start. Pure logic, unit tested with a fixed clock. */
@Component
public class SlotPolicy {

    private final ShowroomProperties rules;
    private final Clock clock;

    public SlotPolicy(ShowroomProperties rules, Clock clock) {
        this.rules = rules;
        this.clock = clock;
    }

    /** Throws 422 with a specific code when the start time is not a bookable slot. */
    public void check(LocalDateTime start) {
        if (start.getSecond() != 0 || start.getNano() != 0 || start.getMinute() % rules.slotMinutes() != 0) {
            throw new UnprocessableException("slot-not-aligned",
                    "Slots start every " + rules.slotMinutes() + " minutes from the hour");
        }
        LocalTime time = start.toLocalTime();
        if (time.isBefore(rules.opensAt()) || time.plusMinutes(rules.slotMinutes()).isAfter(rules.closesAt())) {
            throw new UnprocessableException("outside-opening-hours",
                    "The showroom runs test drives from " + rules.opensAt() + " to " + rules.closesAt());
        }
        if (!isFuture(start)) {
            throw new UnprocessableException("slot-in-past", "That slot has already started");
        }
        checkDate(start.toLocalDate());
    }

    /** Throws 422 when the date is before today or beyond the booking window. */
    public void checkDate(LocalDate date) {
        LocalDate today = LocalDate.now(clock);
        if (date.isBefore(today)) {
            throw new UnprocessableException("slot-in-past", "That day is in the past");
        }
        if (date.isAfter(today.plusDays(rules.maxDaysAhead()))) {
            throw new UnprocessableException("too-far-ahead",
                    "Test drives can be booked up to " + rules.maxDaysAhead() + " days ahead");
        }
    }

    /** Every slot start on a day, in order (10:00, 10:30 ... 17:30 with the default rules). */
    public List<LocalDateTime> slotsOn(LocalDate date) {
        List<LocalDateTime> slots = new ArrayList<>();
        LocalTime last = rules.closesAt().minusMinutes(rules.slotMinutes());
        for (LocalTime t = rules.opensAt(); !t.isAfter(last); t = t.plusMinutes(rules.slotMinutes())) {
            slots.add(date.atTime(t));
        }
        return slots;
    }

    public boolean isFuture(LocalDateTime start) {
        return start.isAfter(LocalDateTime.now(clock));
    }
}
