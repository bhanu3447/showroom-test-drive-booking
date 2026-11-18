package dev.showroom.testdrive.it;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tiny real-HTTP client for the integration tests (JDK HttpClient, no Spring test magic). */
final class HttpApi {

    record Reply(int status, HttpResponse<String> raw, JsonNode json) {
        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String base;

    HttpApi(int port) {
        this.base = "http://localhost:" + port;
    }

    Reply get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET().build());
    }

    Reply post(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    Reply book(String idempotencyKey, long carId, String name, LocalDateTime slot) {
        String body = """
                {"carId":%d,"customerName":"%s","phone":"9876543210","slotStart":"%s"}""".formatted(carId, name, slot);
        return send(HttpRequest.newBuilder(URI.create(base + "/api/bookings"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }

    private Reply send(HttpRequest request) {
        try {
            HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = res.body().isBlank() ? null : json.readTree(res.body());
            return new Reply(res.statusCode(), res, node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
