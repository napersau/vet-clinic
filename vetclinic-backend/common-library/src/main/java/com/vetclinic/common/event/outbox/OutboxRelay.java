package com.vetclinic.common.event.outbox;

import com.vetclinic.common.event.EventRouting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tiến trình gửi outbox của MỘT service (Kiến trúc v4.0 mục 5.3, Quy chuẩn 9.2): mỗi 500 ms lấy các dòng {@code PENDING}
 * đến hạn ({@code FOR UPDATE SKIP LOCKED}), gửi lên exchange {@code clinic.events}, chờ RabbitMQ xác nhận rồi đánh dấu
 * {@code PUBLISHED}. Gửi lỗi: tăng {@code attempt_count}, lùi dần, quá {@code maxAttempts} → {@code FAILED}, và DỪNG lô để
 * giữ đúng thứ tự phát sinh (lần sau gửi tiếp từ chính dòng lỗi).
 * <p>
 * Bảo đảm at-least-once: relay có thể gửi xong nhưng chưa kịp commit đánh dấu → lần sau gửi lại; consumer chống trùng
 * bằng {@code eventId} ({@code processed_events}).
 * <p>
 * Message gửi đi giống hệt cách {@code JacksonJsonMessageConverter} tạo: thân là JSON đã lưu, header {@code __TypeId__}
 * = tên đầy đủ lớp sự kiện, nên consumer hiện có không phải sửa.
 */
@Slf4j
public class OutboxRelay {

    static final String TYPE_ID_HEADER = "__TypeId__";

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRelay(OutboxEventRepository repository, RabbitTemplate rabbitTemplate, OutboxProperties properties, Clock clock) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${vetclinic.outbox.fixed-delay-ms:500}",
            initialDelayString = "${vetclinic.outbox.initial-delay-ms:5000}")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void publishPending() {
        publishBatch();
    }

    /**
     * Gửi một lô; trả về số sự kiện đã gửi thành công (dùng cho kiểm thử và log).
     * <p>
     * READ_COMMITTED là BẮT BUỘC: lô được khóa ({@code FOR UPDATE SKIP LOCKED}) và việc gửi RabbitMQ (chờ xác nhận, có thể treo cả phút khi broker mất kết nối)
     * diễn ra TRONG giao dịch này. Ở REPEATABLE READ mặc định của MySQL, truy vấn khóa tạo khóa khoảng (gap lock) trên chỉ mục
     * {@code (status, next_attempt_at, id)}, nên mọi giao dịch nghiệp vụ khác muốn GHI một dòng outbox mới (thu tiền, chốt ca, tiếp đón...) bị chặn tới khi
     * lock wait timeout (50 giây) rồi lỗi 409 SYS_1005. Ở READ_COMMITTED chỉ khóa đúng các dòng đã chọn, nên ghi dòng mới không bao giờ phải chờ lần gửi.
     * Tìm ra ở bước 8.3 khi tắt RabbitMQ lúc đang thu tiền (EX-18); có kiểm thử hồi quy ở {@code OutboxMySqlIT}.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int publishBatch() {
        List<OutboxEvent> batch = repository.lockNextBatch(clock.instant(), properties.getBatchSize());
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                send(event);
                event.markPublished(clock.instant());
                published++;
            } catch (Exception ex) {
                event.markFailedAttempt(describe(ex), clock.instant(), properties.getMaxAttempts());
                if (event.getStatus() == OutboxStatus.FAILED) {
                    log.error("Outbox {} ({}) gửi lỗi {} lần, chuyển FAILED: {}", event.getEventId(), event.getEventType(),
                            event.getAttemptCount(), event.getLastError());
                } else {
                    log.warn("Outbox {} ({}) gửi lỗi lần {}: {}", event.getEventId(), event.getEventType(),
                            event.getAttemptCount(), event.getLastError());
                }
                break;   // giữ thứ tự: không gửi các sự kiện phía sau khi sự kiện này còn đang lỗi
            }
        }
        if (published > 0) {
            log.debug("Outbox đã gửi {} sự kiện", published);
        }
        return published;
    }

    /** Xóa dòng đã gửi quá thời gian giữ (03:00 giờ VN mỗi ngày). */
    @Scheduled(cron = "${vetclinic.outbox.cleanup-cron:0 0 3 * * *}", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public int cleanup() {
        int deleted = repository.deleteByStatusAndPublishedAtBefore(OutboxStatus.PUBLISHED,
                clock.instant().minus(Duration.ofDays(properties.getRetentionDays())));
        if (deleted > 0) {
            log.info("Đã dọn {} dòng outbox đã gửi", deleted);
        }
        return deleted;
    }

    private void send(OutboxEvent event) throws Exception {
        CorrelationData correlation = new CorrelationData(event.getEventId().toString());
        rabbitTemplate.send(EventRouting.EXCHANGE, event.getRoutingKey(), toMessage(event), correlation);
        if (!publisherConfirmsEnabled()) {
            return;   // không bật publisher confirm: gửi không ném lỗi là xem như thành công (cấu hình nên bật "correlated")
        }
        var confirm = correlation.getFuture().get(properties.getConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
        if (!confirm.ack()) {
            throw new IllegalStateException("RabbitMQ từ chối (nack): " + confirm.reason());
        }
    }

    Message toMessage(OutboxEvent event) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(event.getEventId().toString());
        props.setType(event.getEventType());
        props.setHeader(TYPE_ID_HEADER, properties.getEventPackage() + "." + event.getEventType());
        return new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), props);
    }

    private boolean publisherConfirmsEnabled() {
        ConnectionFactory factory = rabbitTemplate.getConnectionFactory();
        return factory != null && factory.isPublisherConfirms();
    }

    private static String describe(Exception ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
