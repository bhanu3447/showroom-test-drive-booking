package dev.showroom.testdrive.config;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import dev.showroom.testdrive.notification.BookingEventHandler;
import dev.showroom.testdrive.outbox.BookingEvent;
import dev.showroom.testdrive.outbox.BookingEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Picks the event transport with app.events.transport = kafka | in-memory. */
@Configuration
public class EventsConfig {

    /** Real broker: outbox relay produces to Kafka, a listener in this app consumes (group notification-service). */
    @Configuration
    @ConditionalOnProperty(name = "app.events.transport", havingValue = "kafka", matchIfMissing = true)
    static class KafkaTransport {

        @Bean
        NewTopic bookingsTopic(@Value("${app.events.topic}") String topic) {
            return TopicBuilder.name(topic).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic bookingsDeadLetterTopic(@Value("${app.events.topic}") String topic) {
            return TopicBuilder.name(topic + "-dlt").partitions(3).replicas(1).build();
        }

        @Bean
        BookingEventPublisher kafkaBookingEventPublisher(KafkaTemplate<String, String> kafka,
                                                         @Value("${app.events.topic}") String topic) {
            return event -> {
                ProducerRecord<String, String> record =
                        new ProducerRecord<>(topic, event.getAggregateId().toString(), event.getPayload());
                record.headers().add("eventId", event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
                record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
                kafka.send(record).get(10, TimeUnit.SECONDS);   // wait for the broker ack (acks=all)
            };
        }

        /** 3 retries one second apart, then the record goes to <topic>-dlt; bad JSON goes there at once. */
        @Bean
        DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka, @Value("${app.events.topic}") String topic) {
            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                    (rec, ex) -> new TopicPartition(topic + "-dlt", rec.partition()));
            DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
            handler.addNotRetryableExceptions(JacksonException.class);
            return handler;
        }

        @Bean
        BookingEventsListener bookingEventsListener(BookingEventHandler handler, JsonMapper json) {
            return new BookingEventsListener(handler, json);
        }
    }

    public static class BookingEventsListener {

        private final BookingEventHandler handler;
        private final JsonMapper json;

        BookingEventsListener(BookingEventHandler handler, JsonMapper json) {
            this.handler = handler;
            this.json = json;
        }

        @KafkaListener(topics = "${app.events.topic}", groupId = "notification-service")
        public void onBookingEvent(String payload) {
            handler.handle(json.readValue(payload, BookingEvent.class));
        }
    }

    /** No broker: the relay hands each event straight to the same handler (profile 'local'). */
    @Configuration
    @ConditionalOnProperty(name = "app.events.transport", havingValue = "in-memory")
    static class InMemoryTransport {

        @Bean
        BookingEventPublisher inMemoryBookingEventPublisher(BookingEventHandler handler, JsonMapper json) {
            return event -> handler.handle(json.readValue(event.getPayload(), BookingEvent.class));
        }
    }
}
