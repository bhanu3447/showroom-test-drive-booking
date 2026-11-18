package dev.showroom.testdrive.idempotency;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis version: SET key value NX PX lease claims the key atomically across all app instances.
 * Keys look like "idem:booking:&lt;client key&gt;".
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String PREFIX = "idem:";

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public RedisIdempotencyStore(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public Optional<IdempotencyRecord> claim(String key, IdempotencyRecord inProgress, Duration lease) {
        String value = json.writeValueAsString(inProgress);
        for (int attempt = 0; attempt < 3; attempt++) {
            Boolean claimed = redis.opsForValue().setIfAbsent(PREFIX + key, value, lease);
            if (Boolean.TRUE.equals(claimed)) {
                return Optional.empty();
            }
            String existing = redis.opsForValue().get(PREFIX + key);
            if (existing != null) {
                return Optional.of(json.readValue(existing, IdempotencyRecord.class));
            }
            // expired between SET NX and GET: try to claim again
        }
        return Optional.of(inProgress);   // still racing after 3 tries: report "in progress", client retries later
    }

    @Override
    public void complete(String key, IdempotencyRecord done, Duration ttl) {
        redis.opsForValue().set(PREFIX + key, json.writeValueAsString(done), ttl);
    }

    @Override
    public void release(String key) {
        redis.delete(PREFIX + key);
    }
}
