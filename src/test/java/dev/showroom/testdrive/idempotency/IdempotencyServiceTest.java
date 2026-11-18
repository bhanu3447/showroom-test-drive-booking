package dev.showroom.testdrive.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import dev.showroom.testdrive.web.ConflictException;
import dev.showroom.testdrive.web.UnprocessableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Unit test of the idempotency rules against the in-memory store, with a clock the test moves. */
class IdempotencyServiceTest {

    record Req(String slot) {
    }

    record Res(String id) {
    }

    /** A clock the test can move forward. */
    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-18T05:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MovableClock clock = new MovableClock();
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);
    private final IdempotencyService service =
            new IdempotencyService(store, JsonMapper.builder().build(), Duration.ofHours(24), new SimpleMeterRegistry());
    private final AtomicInteger runs = new AtomicInteger();

    private IdempotencyService.Outcome<Res> call(String key, String slot) {
        return service.execute(key, new Req(slot), Res.class, () -> new Res("booking-" + runs.incrementAndGet()));
    }

    @Test
    void sameKeySameBodyReplaysTheFirstResponseWithoutRunningAgain() {
        var first = call("k1", "11:00");
        var second = call("k1", "11:00");

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.response()).isEqualTo(first.response());
        assertThat(runs).hasValue(1);
    }

    @Test
    void sameKeyDifferentBodyIsRejected() {
        call("k2", "11:00");
        assertThatThrownBy(() -> call("k2", "12:00"))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("different request body");
    }

    @Test
    void secondCallWhileTheFirstIsRunningGets409() {
        assertThatThrownBy(() -> service.execute("k3", new Req("11:00"), Res.class, () -> call("k3", "11:00").response()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("still being processed");
    }

    @Test
    void failedActionReleasesTheKeySoARetryRuns() {
        assertThatThrownBy(() -> service.execute("k4", new Req("11:00"), Res.class, () -> {
            throw new IllegalStateException("db down");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(call("k4", "11:00").replayed()).isFalse();
        assertThat(runs).hasValue(1);
    }

    @Test
    void storedResponsesExpireAfterTheTtl() {
        call("k5", "11:00");
        clock.now = clock.now.plus(Duration.ofHours(25));
        assertThat(call("k5", "11:00").replayed()).isFalse();
        assertThat(runs).hasValue(2);
    }

    @Test
    void anAbandonedInProgressClaimFreesItselfAfterTheLease() {
        store.claim("k6", IdempotencyRecord.inProgress(service.fingerprint(new Req("11:00"))), IdempotencyService.LEASE);
        assertThatThrownBy(() -> call("k6", "11:00")).isInstanceOf(ConflictException.class);

        clock.now = clock.now.plus(IdempotencyService.LEASE).plusSeconds(1);
        assertThat(call("k6", "11:00").replayed()).isFalse();
    }
}
