package com.vetclinic.common.event.outbox;

import com.vetclinic.common.event.EventRouting;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.util.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxWriterTest {

    private static final java.util.UUID AGGREGATE = java.util.UUID.fromString("019a9442-d810-7000-8000-00000000002a");

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final JsonMapper mapper = new JsonMapper();
    private final OutboxWriter writer = new OutboxWriter(repository, mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void writesAPendingRowThatCarriesTheEventIdRoutingKeyAndJsonPayload() {
        when(repository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        VisitCompletedEvent event = new VisitCompletedEvent(UuidV7.generate(), NOW, UuidV7.generate(), UuidV7.generate(), "VIS-2026-00042", UuidV7.generate(), false);

        OutboxEvent row = writer.write("VISIT", AGGREGATE, event);

        assertThat(row.getEventId()).isEqualTo(event.eventId());
        assertThat(row.getAggregateType()).isEqualTo("VISIT");
        assertThat(row.getAggregateId()).isEqualTo(AGGREGATE);
        assertThat(row.getEventType()).isEqualTo("VisitCompletedEvent");
        assertThat(row.getRoutingKey()).isEqualTo(EventRouting.VISIT_COMPLETED);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
        // payload đọc ngược lại được đúng sự kiện (consumer dùng cùng JsonMapper)
        assertThat(mapper.readValue(row.getPayload(), VisitCompletedEvent.class)).isEqualTo(event);
    }

    @Test
    void timestampsAreTruncatedToSeconds_becauseMySqlRoundsFractionalSecondsUp() {
        when(repository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        OutboxWriter atFraction = new OutboxWriter(repository, mapper,
                Clock.fixed(Instant.parse("2026-11-20T03:00:00.700Z"), ZoneOffset.UTC));

        OutboxEvent row = atFraction.write("VISIT", AGGREGATE,
                new VisitCompletedEvent(UuidV7.generate(), NOW, null, UuidV7.generate(), "VIS-1", UuidV7.generate(), false));

        assertThat(row.getNextAttemptAt()).isEqualTo(NOW);   // không bị làm tròn lên 03:00:01 (tương lai)
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void mustRunInsideAnExistingTransaction() throws NoSuchMethodException {
        for (Method method : OutboxWriter.class.getDeclaredMethods()) {
            if (method.getName().equals("write")) {
                Transactional tx = method.getAnnotation(Transactional.class);
                assertThat(tx).as(method.toString()).isNotNull();
                assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
            }
        }
    }
}
