package com.vetclinic.common.event.processed;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotentConsumerTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private final ProcessedEventRepository repository = mock(ProcessedEventRepository.class);
    private final IdempotentConsumer consumer = new IdempotentConsumer(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aNewEventRunsTheAction_andIsRecordedForThisConsumer() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        boolean ran = consumer.run(eventId, "appointment.visit-checked-in", runs::incrementAndGet);

        assertThat(ran).isTrue();
        assertThat(runs).hasValue(1);
        ArgumentCaptor<ProcessedEvent> saved = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(new ProcessedEventId(eventId, "appointment.visit-checked-in"));
        assertThat(saved.getValue().getProcessedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().isNew()).isTrue();   // INSERT, không merge
    }

    @Test
    void aDuplicateDeliveryIsSkipped() {
        UUID eventId = UUID.randomUUID();
        when(repository.existsById(new ProcessedEventId(eventId, "reporting.revenue"))).thenReturn(true);
        AtomicInteger runs = new AtomicInteger();

        boolean ran = consumer.run(eventId, "reporting.revenue", runs::incrementAndGet);

        assertThat(ran).isFalse();
        assertThat(runs).hasValue(0);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void theSameEventForAnotherListenerIsNotADuplicate() {
        UUID eventId = UUID.randomUUID();
        when(repository.existsById(new ProcessedEventId(eventId, "notification.a"))).thenReturn(true);
        assertThat(consumer.call(eventId, "clinical.queue-issuer", () -> "issued")).isEqualTo("issued");
    }

    @Test
    void aFailingActionIsNotRecorded_soTheRetryRunsItAgain() {
        UUID eventId = UUID.randomUUID();
        assertThatThrownBy(() -> consumer.run(eventId, "billing.prescription-created", () -> {
            throw new IllegalStateException("db down");
        })).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).saveAndFlush(any());
    }
}
