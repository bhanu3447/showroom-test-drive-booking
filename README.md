# Showroom test-drive booking service

A backend for a car showroom: customers browse the cars on the floor, see free test-drive slots,
and book one. It is small, but it handles the problems real booking systems have:
**double bookings, client retries, lost events and duplicate messages**.

**Stack:** Java 25 · Spring Boot 4.1 (Spring Framework 7, Hibernate 7, Jackson 3) · PostgreSQL 17 · Flyway ·
Redis 8 · Kafka 4.1 (KRaft) · Micrometer + Prometheus · springdoc OpenAPI · JUnit 5, Mockito, Testcontainers ·
Docker · GitHub Actions

| What | How |
|---|---|
| No double booking, even under a race | `slot_holds` table, primary key `(car_id, slot_start)`; the second INSERT fails, mapped to `409 slot-taken` |
| Safe client retries | `Idempotency-Key` header, response stored in Redis (`SET NX` + TTL), replayed on retry |
| No "booked but never announced" | Transactional outbox: booking + slot hold + event row in **one** DB transaction |
| Events to other services | Outbox relay → Kafka topic `showroom.test-drive-bookings.v1` (key = booking id) |
| Duplicate events are harmless | Consumer dedupes by `event_id` (unique key); bad messages go to a `-dlt` topic |
| Fast catalogue | `@Cacheable` car list in Redis, 10 min TTL |
| Clear errors | RFC 9457 problem details with a stable `code` for every business error |
| Operable | Actuator health/readiness/liveness, Prometheus metrics, JSON (ECS) logs with a request id |

## Architecture

```mermaid
flowchart LR
    C[Client / showroom web app] -->|REST + Idempotency-Key| API

    subgraph APP[test-drive-booking  Spring Boot]
        API[Controllers<br/>validation, problem details] --> SVC[BookingService]
        API --> IDEM[IdempotencyService]
        API --> CAT[CarService<br/>@Cacheable]
        SVC -->|one transaction| DBW[(bookings + slot_holds<br/>+ outbox_events)]
        RELAY[OutboxRelay<br/>@Scheduled 500 ms] -->|read unpublished| DBW
        LIS[Kafka listener<br/>group notification-service] --> HND[BookingEventHandler<br/>dedupe by event_id]
    end

    IDEM <-->|SET NX PX / GET| R[(Redis)]
    CAT <-->|cache:cars::active| R
    DBW --- PG[(PostgreSQL<br/>Flyway migrations)]
    RELAY -->|acks=all, idempotent producer| K{{Kafka<br/>showroom.test-drive-bookings.v1}}
    K --> LIS
    K -.->|after 3 retries| DLT{{...-dlt}}
    HND --> PG
```

The consumer lives in the same deployable to keep the project small; it uses its own consumer group and
only talks to the rest of the app through Kafka, so it can move to its own service unchanged.

### Booking sequence

```mermaid
sequenceDiagram
    participant C as Client
    participant A as API
    participant R as Redis
    participant DB as PostgreSQL
    participant K as Kafka
    participant N as Notification consumer

    C->>A: POST /api/bookings (Idempotency-Key: k1)
    A->>R: SET idem:booking:k1 IN_PROGRESS NX PX 30s
    A->>DB: BEGIN; insert booking, slot_hold (PK), outbox row; COMMIT
    A->>R: SET idem:booking:k1 {response} EX 24h
    A-->>C: 201 Created (Idempotent-Replayed: false)
    C->>A: retry with k1 (network timeout on the client)
    A->>R: GET idem:booking:k1
    A-->>C: 201 same body (Idempotent-Replayed: true)
    loop every 500 ms
        A->>DB: select unpublished outbox rows
        A->>K: send(key=bookingId) and wait for ack
        A->>DB: set published_at
    end
    K->>N: TestDriveBooked
    N->>DB: insert notification (unique event_id)
```

## API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/cars` | Active cars (cached) |
| GET | `/api/cars/{id}` | One car, 404 `car-not-found` |
| GET | `/api/cars/{carId}/slots?date=YYYY-MM-DD` | 16 half-hour slots, 10:00 to 17:30, with `available` |
| POST | `/api/bookings` | Header `Idempotency-Key` (8-64 chars) required. 201 + `Location` |
| GET | `/api/bookings/{id}` | Phone number is masked in every response |
| POST | `/api/bookings/{id}/cancel` | Frees the slot; repeating it is safe |
| GET | `/api/bookings/{id}/notifications` | What the event consumer produced (shows the event flowed) |
| GET | `/swagger-ui.html`, `/v3/api-docs` | OpenAPI |
| GET | `/actuator/health`, `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` | Ops |

Error codes: `validation-failed` (400), `car-not-found` / `booking-not-found` (404),
`slot-taken`, `request-in-progress`, `concurrent-update` (409), `slot-not-aligned`, `outside-opening-hours`,
`slot-in-past`, `too-far-ahead`, `already-started`, `idempotency-key-reused` (422).

### Recorded session

Real calls against the app on 2026-11-18 (profile `local`, started from the same layered jar layout the
Dockerfile builds; it listened on port 18080 there, shown as 8080 here). Long bodies trimmed with `...`.

```text
$ curl localhost:8080/api/cars
[{"id":1,"model":"Aurora EV","bodyType":"SUV","fuel":"ELECTRIC","exShowroomPriceInr":1899000},
 {"id":2,"model":"Stratus 4x4",...},{"id":3,"model":"Comet Hatch",...},{"id":4,"model":"Vega Sedan",...}]
# -> HTTP 200

$ curl "localhost:8080/api/cars/1/slots?date=2026-11-19"
[{"start":"2026-11-19T10:00:00","available":true},{"start":"2026-11-19T10:30:00","available":true}, ...
 {"start":"2026-11-19T17:30:00","available":true}]
# -> HTTP 200

$ curl -i -X POST localhost:8080/api/bookings -H 'Content-Type: application/json' -H 'Idempotency-Key: asha-req-0001' \
    -d '{"carId":1,"customerName":"Asha Rao","phone":"9876543210","email":"asha@example.com","slotStart":"2026-11-19T11:00:00"}'
HTTP/1.1 201
X-Request-Id: 5300b325-805b-408e-b1f8-8aa1bd44b9e8
Location: /api/bookings/1a29dad6-e644-4a51-b21e-c7a20d0fc547
Idempotent-Replayed: false
{"id":"1a29dad6-e644-4a51-b21e-c7a20d0fc547","carId":1,"carModel":"Aurora EV","customerName":"Asha Rao",
 "phone":"******3210","email":"asha@example.com","slotStart":"2026-11-19T11:00:00","status":"CONFIRMED",
 "createdAt":"2026-11-18T12:13:18.716259500Z"}

# the same request again (a client retry after a timeout): same booking, nothing new written
HTTP/1.1 201
Location: /api/bookings/1a29dad6-e644-4a51-b21e-c7a20d0fc547
Idempotent-Replayed: true
{"id":"1a29dad6-e644-4a51-b21e-c7a20d0fc547", ... "status":"CONFIRMED", ...}

# another customer, same car, same slot
{"detail":"Aurora EV is already booked at 2026-11-19T11:00","instance":"/api/bookings","status":409,
 "title":"Conflict","type":"https://showroom.example/problems/slot-taken","code":"slot-taken"}

# Asha's key reused for a different body
{"detail":"This Idempotency-Key was already used with a different request body","status":422,
 "type":"https://showroom.example/problems/idempotency-key-reused","code":"idempotency-key-reused", ...}

# 7 pm: showroom closed
{"detail":"The showroom runs test drives from 10:00 to 18:00","status":422,"code":"outside-opening-hours", ...}

# bad fields and a too-short key
{"detail":"One or more fields are invalid","status":400,"code":"validation-failed",
 "errors":{"Idempotency-Key":"must be 8-64 characters: letters, digits, '-' or '_'",
           "phone":"must be a 10-digit Indian mobile number","customerName":"must not be blank"}, ...}

$ curl localhost:8080/api/bookings/1a29dad6-e644-4a51-b21e-c7a20d0fc547/notifications
[{"eventId":"66aaf9b5-2fc8-49c9-9403-fb7a0fc0ba26","channel":"SMS",
  "message":"Hi Asha Rao, your Aurora EV test drive is confirmed for Thu 19 Nov, 11:00. Bring your driving licence.", ...}]

$ curl -X POST localhost:8080/api/bookings/1a29dad6-e644-4a51-b21e-c7a20d0fc547/cancel     # twice: same answer both times
{"id":"1a29dad6-...","status":"CANCELLED", ...}
# -> HTTP 200

$ curl localhost:8080/api/bookings/1a29dad6-e644-4a51-b21e-c7a20d0fc547/notifications
[{... "message":"Hi Asha Rao, your Aurora EV test drive is confirmed for Thu 19 Nov, 11:00. ..."},
 {... "message":"Hi Asha Rao, your Aurora EV test drive on Thu 19 Nov, 11:00 is cancelled.", ...}]

$ curl -s localhost:8080/actuator/prometheus | grep ^showroom_
showroom_bookings_cancelled_total 1.0
showroom_bookings_confirmed_total 1.0
showroom_bookings_rejected_total{detected="pre-check",reason="slot-taken"} 1.0
showroom_events_consumed_total{type="TestDriveBooked"} 1.0
showroom_events_consumed_total{type="TestDriveCancelled"} 1.0
showroom_idempotency_total{result="key-reused"} 1.0
showroom_idempotency_total{result="replayed"} 1.0
showroom_outbox_pending 0.0
showroom_outbox_published_total 2.0
```

`/actuator/health` returned `"status":"UP"` with `db`, `diskSpace`, `livenessState`, `readinessState`; OpenAPI
listed all seven `/api` paths and `/swagger-ui.html` answered 200.

## How to run

### Whole stack in Docker (PostgreSQL, Redis, Kafka, app)

```bash
docker compose up --build
# API: http://localhost:8080   Swagger UI: http://localhost:8080/swagger-ui.html
# Kafka from the host: localhost:29092
```

### Without Docker (profile `local`)

Needs only JDK 25. H2 in PostgreSQL mode replaces PostgreSQL (same Flyway migrations), an in-memory
hand-off replaces Kafka (same outbox relay and same consumer handler), an in-memory map replaces the
Redis idempotency store (same interface, same expiry rules), and the cache is in-process.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# or
./mvnw package -DskipTests && java -jar target/test-drive-booking-1.0.0.jar --spring.profiles.active=local
```

### Against your own PostgreSQL/Redis/Kafka

Default profile; set `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `KAFKA_BOOTSTRAP`.

### Tests

```bash
./mvnw test     # unit + slice + local integration tests: no Docker needed
./mvnw verify   # + Testcontainers integration test (PostgreSQL, Redis, Kafka): needs Docker
```

`.mvn/maven.config` points Maven at `.mvn/central-settings.xml`, which resolves everything from Maven
Central. That keeps the build independent of any mirror configured in `~/.m2/settings.xml`.

## Tests: what ran where

| Test class | Kind | What it proves | Ran on the dev machine (no Docker) | CI |
|---|---|---|---|---|
| `SlotPolicyTest` (10) | Unit, fixed clock | Opening hours, alignment, past, booking window, 16 slots, phone masking | Yes, passed | Yes |
| `IdempotencyServiceTest` (6) | Unit | Replay, key reuse 422, in-progress 409, release on failure, TTL and lease expiry | Yes, passed | Yes |
| `BookingControllerTest` (5) | `@WebMvcTest` slice | 201 + Location + replay header, validation problem with field and header errors, 409, bad JSON | Yes, passed | Yes |
| `PersistenceSliceTest` (4) | `@DataJpaTest` slice on H2 + Flyway | Seed data, slot primary key rejects a second hold, release, versioned update | Yes, passed | Yes |
| `BookingFlowLocalTest` (5) | Full app on a random port, real HTTP | Book, replay, notification via outbox, cancel, rebook; **8 parallel customers for 1 slot: exactly one 201, seven 409**; consumer dedupe; problem details; actuator + OpenAPI | Yes, passed | Yes |
| `BookingFlowContainersIT` (1) | Testcontainers: PostgreSQL 17, Redis 8, Kafka 4.1 | Same flow on the real stack: Redis cache and idempotency keys exist, PostgreSQL PK gives 409, event goes through Kafka to the listener, Redis health UP | **No: skipped (no Docker here)** | Yes (runner has Docker) |

Not run on the dev machine because Docker is not installed there: the Testcontainers IT, `docker compose up`,
`docker build`. Their files were checked by parsing (SnakeYAML) and by running the same layered-jar
extraction and `JarLauncher` start the Dockerfile uses. The first CI run is the first real run of the
Kafka and Redis code paths and of the image build.

## Design decisions and trade-offs

1. **Database constraint as the real guard.** The service checks `existsById` first (a friendly fast path), but
   two requests can both pass that check. The `(car_id, slot_start)` primary key on `slot_holds` decides. A
   separate hold table (instead of a partial unique index on `bookings`) keeps the SQL portable to H2 and
   makes cancel a simple `DELETE`. `SlotHold` implements `Persistable.isNew() = true`, because with an
   assigned id Spring Data's `save()` would otherwise `merge` and silently move an existing hold to the new
   booking.
2. **Idempotency keys with stored responses**, not just "dedupe": a retry gets the *same* response. The
   in-progress claim has a 30 s lease, the stored response 24 h. If the app dies mid-request the key frees
   itself quickly, and the slot primary key still blocks a second booking. Failed requests release the key
   (simpler than storing errors, unlike Stripe); the trade-off is that a retry after a 409 runs again and
   gets 409 again.
3. **Transactional outbox instead of "save then send".** Sending to Kafka inside the request can succeed while
   the DB commit fails (phantom event) or the reverse (lost event). The outbox row commits with the booking;
   the relay publishes later. Delivery is **at-least-once**, so the consumer is idempotent (`event_id` unique).
   Polling was chosen over CDC (Debezium) to avoid running Kafka Connect; the cost is up to 500 ms latency and
   one query per poll. The relay stops at the first failure, so events of one booking are never reordered.
   With several app instances, add `SELECT ... FOR UPDATE SKIP LOCKED` or ShedLock so they do not double-send.
4. **Kafka key = booking id**, 3 partitions: per-booking ordering, parallelism across bookings. Producer
   `acks=all` + idempotence. Consumer: 3 retries 1 s apart, then the `-dlt` topic; JSON errors go to the DLT
   at once (retrying cannot fix them).
5. **Optimistic locking** (`@Version`) on bookings: two concurrent cancels cannot both write; the loser gets
   `409 concurrent-update`.
6. **Cache only the catalogue.** Cars change rarely and are read on every page. Slot availability is never
   cached: a stale "available" would just produce 409s.
7. **Problem details everywhere** with a stable `code` and `type` URI, so clients branch on codes, not on text.
8. **Time:** one injected `Clock`, slot times in showroom local time (`Asia/Kolkata`), audit times as UTC
   `Instant`. Single time zone by design; a multi-city chain would store the zone per showroom.
9. **Profiles instead of mocks for local runs.** The `local` profile swaps infrastructure behind the same
   interfaces (`IdempotencyStore`, `BookingEventPublisher`), so the no-Docker tests exercise the real
   controllers, services, transactions, outbox relay and consumer handler.

**Not built (on purpose):** authentication (would be Spring Security + JWT resource server), multi-instance
relay locking, rate limiting (Redis token bucket would sit next to the idempotency store), a separate
deployable for the consumer.

## A real bug this project caught

The first version of `OutboxRelay` had a `@Scheduled poll()` that called its own `@Transactional
relayBatch()`. Self-invocation bypasses the Spring proxy, so there was **no transaction**: `published_at`
was never saved and every event was re-sent every 500 ms. Nothing broke for users because the consumer
dedupes by `event_id`, but the metrics gave it away (`showroom_events_duplicates_total 58`,
`showroom_outbox_pending 2` after a few seconds). Fix: one public method that is both `@Scheduled` and
`@Transactional`, called through the proxy. `BookingFlowLocalTest` now asserts that the outbox drains to 0.

## What to say in an interview

- **"How do you stop double booking?"** A unique constraint in the database, because application checks race.
  Show `slot_holds` and the 8-thread test: exactly one 201, seven 409.
- **"The client timed out and retried. Did you book twice?"** No: `Idempotency-Key`, Redis `SET NX` claim with
  a lease, stored response replayed with `Idempotent-Replayed: true`; same key with another body is a 422.
- **"How do you publish an event reliably?"** Transactional outbox, at-least-once relay, idempotent consumer,
  DLT. Explain why "save then send" is a dual write.
- **"Exactly once?"** Not end to end; at-least-once delivery plus an idempotent effect (unique `event_id`).
- **"How would you scale it?"** Stateless app behind a load balancer; Redis shared; relay with `SKIP LOCKED`;
  more Kafka partitions; read replicas for slot queries.
- **"How do you know it works in production?"** Readiness/liveness probes, `showroom_outbox_pending` (alert if
  it grows), rejection and duplicate counters, JSON logs with `requestId`.
- **The bug story above:** a Spring proxy pitfall, found through metrics, survived thanks to an idempotent consumer.

## Project layout

```
src/main/java/dev/showroom/testdrive/
  catalog/       Car, CarService (@Cacheable), CarController
  booking/       Booking, SlotHold/SlotKey, SlotPolicy, BookingService, BookingController
  idempotency/   IdempotencyService, IdempotencyStore (Redis | in-memory)
  outbox/        BookingEvent, OutboxEvent, OutboxWriter, OutboxRelay, BookingEventPublisher
  notification/  BookingEventHandler (consumer logic), Notification, NotificationController
  config/        AppConfig (Clock, OpenAPI), EventsConfig (Kafka | in-memory), IdempotencyConfig
  web/           GlobalExceptionHandler (problem details), RequestIdFilter, ApiException types
src/main/resources/db/migration/   V1__schema.sql, V2__seed_cars.sql
docker-compose.yml, Dockerfile, .github/workflows/ci.yml
```
