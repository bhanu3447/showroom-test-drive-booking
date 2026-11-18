package dev.showroom.testdrive.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Single-instance stand-in for Redis (profile 'local' and unit tests). Same contract, same expiry rules. */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Entry(IdempotencyRecord record, Instant expiresAt) {
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<IdempotencyRecord> claim(String key, IdempotencyRecord inProgress, Duration lease) {
        Instant now = clock.instant();
        Entry mine = new Entry(inProgress, now.plus(lease));
        Entry winner = entries.compute(key, (k, old) -> old == null || !old.expiresAt().isAfter(now) ? mine : old);
        return winner == mine ? Optional.empty() : Optional.of(winner.record());
    }

    @Override
    public void complete(String key, IdempotencyRecord done, Duration ttl) {
        entries.put(key, new Entry(done, clock.instant().plus(ttl)));
    }

    @Override
    public void release(String key) {
        entries.remove(key);
    }
}
