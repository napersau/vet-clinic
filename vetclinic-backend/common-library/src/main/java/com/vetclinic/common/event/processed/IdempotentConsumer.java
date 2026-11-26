package com.vetclinic.common.event.processed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Xử lý sự kiện đúng một lần dù RabbitMQ / outbox giao nhiều lần (Quy chuẩn v4.0 mục 9.2, EX-19, NFR-09).
 * <pre>
 * &#64;RabbitListener(queues = "appointment.visit-checked-in")
 * public void on(VisitCheckedInEvent e) {
 *     idempotent.run(e.eventId(), "appointment.visit-checked-in", () -> appointmentService.markCheckedIn(e));
 * }
 * </pre>
 * Hành động và dòng {@code processed_events} nằm trong MỘT giao dịch: hành động lỗi thì không ghi dòng (RabbitMQ thử lại);
 * hai bản nhận đồng thời thì bản sau vấp khóa chính, cả giao dịch rollback, lần thử lại thấy đã xử lý và bỏ qua.
 * Bean được tạo bởi {@link IdempotentConsumerConfiguration} khi service gắn {@link EnableIdempotentConsumer}.
 */
@Slf4j
public class IdempotentConsumer {

    private final ProcessedEventRepository repository;
    private final Clock clock;

    public IdempotentConsumer(ProcessedEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** @return {@code true} nếu đã chạy hành động; {@code false} nếu sự kiện đã được listener này xử lý trước đó. */
    @Transactional(rollbackFor = Exception.class)
    public boolean run(UUID eventId, String consumer, Runnable action) {
        return call(eventId, consumer, () -> {
            action.run();
            return Boolean.TRUE;
        }) != null;
    }

    /** Như {@link #run} nhưng trả kết quả của hành động; {@code null} nếu đã xử lý trước đó. */
    @Transactional(rollbackFor = Exception.class)
    public <T> T call(UUID eventId, String consumer, Supplier<T> action) {
        ProcessedEventId id = new ProcessedEventId(eventId, consumer);
        if (repository.existsById(id)) {
            log.info("Bỏ qua sự kiện {} đã được {} xử lý", eventId, consumer);
            return null;
        }
        T result = action.get();
        repository.saveAndFlush(new ProcessedEvent(id, clock.instant()));   // flush ngay: trùng khóa thì lỗi trong giao dịch này
        return result;
    }
}
