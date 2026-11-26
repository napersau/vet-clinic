package com.vetclinic.common.event.outbox;

import com.vetclinic.common.event.BaseEvent;
import com.vetclinic.common.event.DomainEvent;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Ghi sự kiện vào {@code outbox_events} trong CÙNG giao dịch với thay đổi nghiệp vụ (Quy chuẩn v4.0 mục 9.2, NFR-09):
 * hoặc cả hai cùng được commit, hoặc cả hai cùng rollback, nên không còn "đã commit mà mất sự kiện".
 * {@code Propagation.MANDATORY}: gọi ngoài giao dịch là lỗi lập trình ({@code IllegalTransactionStateException}).
 * <pre>
 * &#64;Transactional
 * public void close(...) {
 *     visit.setStatus(WAITING_PAYMENT);
 *     outboxWriter.write("VISIT", visit.getId(), new VisitCompletedEvent(UuidV7.generate(clock), ...));
 * }
 * </pre>
 * Bean được tạo bởi {@link OutboxConfiguration} khi service gắn {@link EnableOutbox}.
 */
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Sự kiện đã tự biết routing key của mình. */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent write(String aggregateType, UUID aggregateId, DomainEvent event) {
        return write(aggregateType, aggregateId, event.routingKey(), event);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent write(String aggregateType, UUID aggregateId, String routingKey, BaseEvent event) {
        Objects.requireNonNull(event.eventId(), "Sự kiện phải có eventId");
        // Cột TIMESTAMP của MySQL không giữ phần lẻ giây và LÀM TRÒN (10:00:00.7 -> 10:00:01): nếu không cắt về giây,
        // next_attempt_at có thể nằm ở tương lai và relay chạy ngay sau đó coi sự kiện là "chưa đến hạn".
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        return repository.save(OutboxEvent.builder()
                .eventId(event.eventId())
                .aggregateType(aggregateType)
                .aggregateId(Objects.requireNonNull(aggregateId, "aggregateId"))
                .eventType(event.getClass().getSimpleName())
                .routingKey(routingKey)
                .payload(objectMapper.writeValueAsString(event))
                .status(OutboxStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build());
    }
}
