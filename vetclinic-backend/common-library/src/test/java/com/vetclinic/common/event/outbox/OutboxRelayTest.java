package com.vetclinic.common.event.outbox;

import com.vetclinic.common.event.EventRouting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
    private final OutboxProperties properties = new OutboxProperties();
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        when(rabbitTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        properties.setConfirmTimeoutMs(200);
        relay = new OutboxRelay(repository, rabbitTemplate, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OutboxEvent row(String type, String routingKey, String payload) {
        return OutboxEvent.builder().eventId(UUID.randomUUID()).aggregateType("VISIT").aggregateId(UUID.randomUUID())
                .eventType(type).routingKey(routingKey).payload(payload).status(OutboxStatus.PENDING)
                .nextAttemptAt(NOW).createdAt(NOW).build();
    }

    @Test
    void sendsEachDueEventToClinicEvents_likeTheJsonConverterWould_andMarksItPublished() {
        OutboxEvent a = row("VisitCompletedEvent", "visit.completed", "{\"visitId\":1,\"note\":\"Khám lâm sàng\"}");
        OutboxEvent b = row("MedicineLowStockEvent", "pharmacy.low_stock", "{}");
        when(repository.lockNextBatch(NOW, 100)).thenReturn(List.of(a, b));

        int sent = relay.publishBatch();

        assertThat(sent).isEqualTo(2);
        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(EventRouting.EXCHANGE), eq("visit.completed"), message.capture(), any(CorrelationData.class));
        Message m = message.getValue();
        assertThat(new String(m.getBody(), StandardCharsets.UTF_8)).isEqualTo(a.getPayload());
        assertThat(m.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(m.getMessageProperties().getMessageId()).isEqualTo(a.getEventId().toString());
        assertThat((String) m.getMessageProperties().getHeader("__TypeId__")).isEqualTo("com.vetclinic.common.event.VisitCompletedEvent");
        assertThat(a.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(b.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(a.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void whenRabbitMqIsDown_theEventStaysPendingForARetry_andLaterEventsWaitToKeepTheOrder() {
        OutboxEvent first = row("VisitCompletedEvent", "visit.completed", "{}");
        OutboxEvent second = row("VisitCompletedEvent", "visit.completed", "{}");
        when(repository.lockNextBatch(NOW, 100)).thenReturn(List.of(first, second));
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        int sent = relay.publishBatch();

        assertThat(sent).isZero();
        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(first.getLastError()).contains("Connection refused");
        assertThat(second.getAttemptCount()).isZero();   // không gửi vượt lên trước
        verify(rabbitTemplate, times(1)).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void withPublisherConfirms_aNackCountsAsAFailedAttempt() {
        when(connectionFactory.isPublisherConfirms()).thenReturn(true);
        OutboxEvent event = row("VisitCompletedEvent", "visit.completed", "{}");
        when(repository.lockNextBatch(NOW, 100)).thenReturn(List.of(event));
        doAnswer(inv -> {
            CorrelationData cd = inv.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(false, "queue full"));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        relay.publishBatch();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).contains("queue full");
    }

    @Test
    void withPublisherConfirms_anAckMarksPublished_andAMissingConfirmTimesOut() {
        when(connectionFactory.isPublisherConfirms()).thenReturn(true);
        OutboxEvent acked = row("VisitCompletedEvent", "visit.completed", "{}");
        when(repository.lockNextBatch(NOW, 100)).thenReturn(List.of(acked));
        doAnswer(inv -> {
            ((CorrelationData) inv.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        relay.publishBatch();
        assertThat(acked.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);

        OutboxEvent silent = row("VisitCompletedEvent", "visit.completed", "{}");
        when(repository.lockNextBatch(NOW, 100)).thenReturn(List.of(silent));
        doAnswer(inv -> null).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        relay.publishBatch();
        assertThat(silent.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(silent.getLastError()).contains("Timeout");
    }

    @Test
    void anEmptyBatchSendsNothing() {
        when(repository.lockNextBatch(any(Instant.class), anyInt())).thenReturn(List.of());
        assertThat(relay.publishBatch()).isZero();
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void cleanupDeletesPublishedRowsOlderThanTheRetention() {
        when(repository.deleteByStatusAndPublishedAtBefore(OutboxStatus.PUBLISHED, NOW.minusSeconds(7 * 24 * 3600))).thenReturn(3);
        assertThat(relay.cleanup()).isEqualTo(3);
    }
}
