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
