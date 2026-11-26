package com.vetclinic.common.event.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    static OutboxEvent pending() {
        return OutboxEvent.builder().eventId(UUID.randomUUID()).aggregateType("VISIT").aggregateId(UUID.randomUUID())
                .eventType("VisitCompletedEvent").routingKey("visit.completed").payload("{}")
                .status(OutboxStatus.PENDING).nextAttemptAt(NOW).createdAt(NOW).build();
    }

    @Test
    void backoffDoublesFromOneSecond_andIsCappedAtFiveMinutes() {
        assertThat(OutboxEvent.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OutboxEvent.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxEvent.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(OutboxEvent.backoff(9)).isEqualTo(Duration.ofSeconds(256));
        assertThat(OutboxEvent.backoff(10)).isEqualTo(Duration.ofMinutes(5));
        assertThat(OutboxEvent.backoff(64)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aFailedAttemptStaysPending_andIsRetriedLater() {
        OutboxEvent event = pending();

        event.markFailedAttempt("Connection refused", NOW, 10);
        event.markFailedAttempt("Connection refused", NOW, 10);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(2);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(event.getLastError()).isEqualTo("Connection refused");
    }

    @Test
    void theTenthFailureMarksFailed() {
        OutboxEvent event = pending();
        for (int i = 0; i < 10; i++) {
            event.markFailedAttempt("nack", NOW, 10);
        }
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(10);
    }

    @Test
    void longErrorsAreTruncatedToTheColumnSize() {
        OutboxEvent event = pending();
        event.markFailedAttempt("x".repeat(2000), NOW, 10);
        assertThat(event.getLastError()).hasSize(500);
    }

    @Test
    void publishingClearsTheLastError() {
        OutboxEvent event = pending();
        event.markFailedAttempt("timeout", NOW, 10);
        event.markPublished(NOW.plusSeconds(3));
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(event.getLastError()).isNull();
    }
}
