package dev.showroom.testdrive.booking;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        Optional<LocalTime> last = lastStart();
        if (last.isEmpty() || time.isBefore(rules.opensAt()) || time.isAfter(last.get())) {
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
        Optional<LocalTime> last = lastStart();
        if (last.isEmpty()) {
            return slots;
        }
        // t <= last means t + slot <= closesAt, so plusMinutes cannot wrap past midnight here.
        for (LocalTime t = rules.opensAt(); !t.isAfter(last.get()); t = t.plusMinutes(rules.slotMinutes())) {
            slots.add(date.atTime(t));
        }
        return slots;
    }

    /**
     * The latest start whose slot still ends at or before closing on the same day, or empty when no slot fits.
     * LocalTime arithmetic wraps at midnight (23:30 + 30 min = 00:00), so the subtraction is guarded: a slot
     * longer than the time from midnight to closing, or longer than the opening hours, leaves no valid start.
     */
    private Optional<LocalTime> lastStart() {
        long slotSeconds = rules.slotMinutes() * 60L;
        if (slotSeconds > rules.closesAt().toSecondOfDay()) {
            return Optional.empty();
        }
        LocalTime last = rules.closesAt().minusSeconds(slotSeconds);
        return last.isBefore(rules.opensAt()) ? Optional.empty() : Optional.of(last);
    }

    public boolean isFuture(LocalDateTime start) {
        return start.isAfter(LocalDateTime.now(clock));
    }
}
