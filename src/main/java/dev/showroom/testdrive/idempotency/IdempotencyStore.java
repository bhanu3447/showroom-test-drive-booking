package dev.showroom.testdrive.idempotency;

import java.time.Duration;
import java.util.Optional;

/** Key-value store with "set if absent" and expiry. Redis in production, a map in the 'local' profile. */
public interface IdempotencyStore {

    /**
     * Atomically claims the key. Returns empty if this caller now owns it,
     * or the record already stored by an earlier request.
     */
    Optional<IdempotencyRecord> claim(String key, IdempotencyRecord inProgress, Duration lease);

    /** Stores the finished response (overwrites the in-progress claim). */
    void complete(String key, IdempotencyRecord done, Duration ttl);

    /** Forgets the key so the client may retry (used when the request failed). */
    void release(String key);
}
