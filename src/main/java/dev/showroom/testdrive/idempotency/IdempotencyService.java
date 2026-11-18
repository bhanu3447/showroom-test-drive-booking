package dev.showroom.testdrive.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import dev.showroom.testdrive.web.ConflictException;
import dev.showroom.testdrive.web.UnprocessableException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotency keys with stored responses (the Stripe pattern):
 * <ul>
 *   <li>first request with a key: claim it (short lease), run, store the response for {@code ttl};</li>
 *   <li>same key + same body again: replay the stored response, do not book twice;</li>
 *   <li>same key + different body: 422 (a client bug);</li>
 *   <li>same key while the first is still running: 409, retry later;</li>
 *   <li>the action failed: the key is released, so a retry runs again.</li>
 * </ul>
 * The lease is short on purpose: if the app dies mid-request the key frees itself in seconds,
 * and the slot's primary key still stops a double booking on the retry.
 */
@Service
public class IdempotencyService {

    static final Duration LEASE = Duration.ofSeconds(30);

    private final IdempotencyStore store;
    private final JsonMapper json;
    private final Duration ttl;
    private final MeterRegistry meters;

    public IdempotencyService(IdempotencyStore store, JsonMapper json, @Value("${app.idempotency.ttl:24h}") Duration ttl,
                              MeterRegistry meters) {
        this.store = store;
        this.json = json;
        this.ttl = ttl;
        this.meters = meters;
    }

    public record Outcome<T>(T response, boolean replayed) {
    }

    public <T> Outcome<T> execute(String key, Object request, Class<T> responseType, Supplier<T> action) {
        String fingerprint = fingerprint(request);
        Optional<IdempotencyRecord> earlier = store.claim(key, IdempotencyRecord.inProgress(fingerprint), LEASE);
        if (earlier.isPresent()) {
            return replay(earlier.get(), fingerprint, responseType);
        }
        T response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            store.release(key);
            throw e;
        }
        store.complete(key, IdempotencyRecord.completed(fingerprint, json.writeValueAsString(response)), ttl);
        return new Outcome<>(response, false);
    }

    private <T> Outcome<T> replay(IdempotencyRecord earlier, String fingerprint, Class<T> responseType) {
        if (!earlier.fingerprint().equals(fingerprint)) {
            meters.counter("showroom.idempotency", "result", "key-reused").increment();
            throw new UnprocessableException("idempotency-key-reused",
                    "This Idempotency-Key was already used with a different request body");
        }
        if (!earlier.completed()) {
            meters.counter("showroom.idempotency", "result", "in-progress").increment();
            throw new ConflictException("request-in-progress",
                    "A request with this Idempotency-Key is still being processed; retry shortly");
        }
        meters.counter("showroom.idempotency", "result", "replayed").increment();
        return new Outcome<>(json.readValue(earlier.responseJson(), responseType), true);
    }

    String fingerprint(Object request) {
        try {
            byte[] body = json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
