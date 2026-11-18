package dev.showroom.testdrive.config;

import java.time.Clock;

import dev.showroom.testdrive.idempotency.IdempotencyStore;
import dev.showroom.testdrive.idempotency.InMemoryIdempotencyStore;
import dev.showroom.testdrive.idempotency.RedisIdempotencyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Picks the idempotency store with app.idempotency.store = redis | in-memory. */
@Configuration
public class IdempotencyConfig {

    @Bean
    @ConditionalOnProperty(name = "app.idempotency.store", havingValue = "redis", matchIfMissing = true)
    IdempotencyStore redisIdempotencyStore(StringRedisTemplate redis, JsonMapper json) {
        return new RedisIdempotencyStore(redis, json);
    }

    @Bean
    @ConditionalOnProperty(name = "app.idempotency.store", havingValue = "in-memory")
    IdempotencyStore inMemoryIdempotencyStore(Clock clock) {
        return new InMemoryIdempotencyStore(clock);
    }
}
