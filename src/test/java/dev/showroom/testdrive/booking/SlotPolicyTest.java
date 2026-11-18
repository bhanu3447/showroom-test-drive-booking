package dev.showroom.testdrive.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import dev.showroom.testdrive.config.ShowroomProperties;
import dev.showroom.testdrive.web.UnprocessableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit test: plain Java, fixed clock (2026-11-18 09:00 in Kolkata), no Spring. */
class SlotPolicyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

    private final SlotPolicy policy = new SlotPolicy(
            new ShowroomProperties(ZONE, LocalTime.of(10, 0), LocalTime.of(18, 0), 30, 30),
            Clock.fixed(TODAY.atTime(9, 0).atZone(ZONE).toInstant(), ZONE));

    @Test
    void acceptsAnAlignedSlotInsideOpeningHours() {
        assertThatCode(() -> policy.check(TODAY.atTime(10, 0))).doesNotThrowAnyException();
        assertThatCode(() -> policy.check(TODAY.plusDays(30).atTime(17, 30))).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "2026-11-18T10:15:00, slot-not-aligned",
            "2026-11-18T10:00:30, slot-not-aligned",
            "2026-11-18T09:30:00, outside-opening-hours",
            "2026-11-18T18:00:00, outside-opening-hours",
            "2026-11-17T11:00:00, slot-in-past",
            "2026-12-19T11:00:00, too-far-ahead"
    })
    void rejectsWithASpecificCode(LocalDateTime start, String code) {
        assertThatThrownBy(() -> policy.check(start))
                .isInstanceOf(UnprocessableException.class)
                .extracting(e -> ((UnprocessableException) e).code())
                .isEqualTo(code);
    }

    @Test
    void aDayHasSixteenHalfHourSlotsFromTenToFiveThirty() {
        var slots = policy.slotsOn(TODAY);
        assertThat(slots).hasSize(16);
        assertThat(slots.getFirst()).isEqualTo(TODAY.atTime(10, 0));
        assertThat(slots.getLast()).isEqualTo(TODAY.atTime(17, 30));
    }

    @Test
    void aSlotThatWouldEndAfterMidnightIsRejected() {
        // 23:30 + 30 min wraps to 00:00, which must not count as "before 18:00 closing".
        assertThatThrownBy(() -> policy.check(TODAY.atTime(23, 30)))
                .isInstanceOf(UnprocessableException.class)
                .extracting(e -> ((UnprocessableException) e).code())
                .isEqualTo("outside-opening-hours");
    }

    @Test
    void theLastStartIsClosingMinusOneSlotAndAMinuteLaterIsRejected() {
        assertThatCode(() -> policy.check(TODAY.atTime(17, 30))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.check(TODAY.atTime(17, 31))).isInstanceOf(UnprocessableException.class);

        // One-minute slots isolate the closing-time rule from the alignment rule, right next to midnight.
        SlotPolicy lateNight = policy(LocalTime.of(22, 0), LocalTime.of(23, 59), 1);
        assertThatCode(() -> lateNight.check(TODAY.atTime(23, 58))).doesNotThrowAnyException();
        assertThatThrownBy(() -> lateNight.check(TODAY.atTime(23, 59)))
                .isInstanceOf(UnprocessableException.class)
                .extracting(e -> ((UnprocessableException) e).code())
                .isEqualTo("outside-opening-hours");
        SlotPolicy lateHalfHours = policy(LocalTime.of(10, 0), LocalTime.of(23, 59), 30);
        assertThatCode(() -> lateHalfHours.check(TODAY.atTime(23, 0))).doesNotThrowAnyException();
        assertThatThrownBy(() -> lateHalfHours.check(TODAY.atTime(23, 30))).isInstanceOf(UnprocessableException.class);
    }

    @ParameterizedTest(name = "{0}-{1} every {2} min")
    @CsvSource({
            "10:00, 18:00, 30",
            "10:00, 23:59, 30",
            "22:00, 23:59, 1",
            "00:00, 00:30, 100"
    })
    void listedSlotsStartAfterOpeningAndEndByClosingOnTheSameDay(LocalTime opens, LocalTime closes, int minutes) {
        SlotPolicy p = policy(opens, closes, minutes);
        LocalDate day = TODAY.plusDays(1);
        for (LocalDateTime start : p.slotsOn(day)) {
            assertThat(start.toLocalDate()).isEqualTo(day);
            assertThat(start.toLocalTime()).isAfterOrEqualTo(opens);
            assertThat(start.plusMinutes(minutes)).isBeforeOrEqualTo(day.atTime(closes));
        }
    }

    @ParameterizedTest(name = "{0}-{1} every {2} min")
    @CsvSource({
            "10:00, 11:00, 120",
            "00:00, 00:30, 100"
    })
    void aSlotLongerThanTheOpeningHoursGivesNoSlots(LocalTime opens, LocalTime closes, int minutes) {
        SlotPolicy p = policy(opens, closes, minutes);
        assertThat(p.slotsOn(TODAY.plusDays(1))).isEmpty();
        assertThatThrownBy(() -> p.check(TODAY.plusDays(1).atTime(opens)))
                .isInstanceOf(UnprocessableException.class)
                .extracting(e -> ((UnprocessableException) e).code())
                .isEqualTo("outside-opening-hours");
    }

    private static SlotPolicy policy(LocalTime opens, LocalTime closes, int slotMinutes) {
        return new SlotPolicy(new ShowroomProperties(ZONE, opens, closes, slotMinutes, 30),
                Clock.fixed(TODAY.atTime(9, 0).atZone(ZONE).toInstant(), ZONE));
    }

    @Test
    void slotsAlreadyStartedAreNotFuture() {
        assertThat(policy.isFuture(TODAY.atTime(9, 0))).isFalse();
        assertThat(policy.isFuture(TODAY.atTime(9, 30))).isTrue();
    }

    @Test
    void maskKeepsOnlyTheLastFourDigits() {
        assertThat(BookingResponse.mask("9876543210")).isEqualTo("******3210");
        assertThat(BookingResponse.mask("123")).isEqualTo("****");
    }
}
