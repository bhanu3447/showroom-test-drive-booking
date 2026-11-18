package dev.showroom.testdrive.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The real stack in containers: PostgreSQL 17, Redis 8, Kafka 4.1 (KRaft). Default profile, so the
 * Kafka publisher/listener, the Redis idempotency store and the Redis cache are the ones under test.
 * Needs Docker: runs in GitHub Actions; skipped (not failed) on a machine without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingFlowContainersIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final LocalDate TOMORROW = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void bookingFlowsThroughPostgresRedisAndKafka() {
        HttpApi api = new HttpApi(port);
        LocalDateTime slot = TOMORROW.atTime(10, 30);
        String key = HttpApi.newKey();

        assertThat(api.get("/api/cars").json()).hasSize(4);
        assertThat(redisTemplate.hasKey("cache:cars::active")).isTrue();          // catalogue cached in Redis

        HttpApi.Reply first = api.book(key, 1, "Asha Rao", slot);
        assertThat(first.status()).isEqualTo(201);
        String id = first.json().get("id").asString();
        assertThat(redisTemplate.hasKey("idem:booking:" + key)).isTrue();         // response stored in Redis

        HttpApi.Reply retry = api.book(key, 1, "Asha Rao", slot);
        assertThat(retry.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.json().get("id").asString()).isEqualTo(id);

        assertThat(api.book(HttpApi.newKey(), 1, "Ravi", slot).status()).isEqualTo(409);   // PostgreSQL primary key

        // outbox -> relay -> Kafka topic -> @KafkaListener -> notifications table
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            JsonNode notes = api.get("/api/bookings/" + id + "/notifications").json();
            assertThat(notes).hasSize(1);
            assertThat(notes.get(0).get("message").asString()).contains("confirmed");
        });

        assertThat(api.get("/actuator/health").json().get("components").has("redis")).isTrue();
        assertThat(api.get("/actuator/health").json().get("status").asString()).isEqualTo("UP");
    }
}
