package com.vetclinic.common.event.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Một dòng {@code outbox_events} (schema_init.sql v4.0 mục 9): sự kiện được ghi CÙNG giao dịch với thay đổi nghiệp vụ,
 * {@link OutboxRelay} gửi ra RabbitMQ sau. Không có entity nào của service trỏ vào bảng này.
 * <p>
 * {@code aggregate_id} là {@code BINARY(16)} / {@link UUID} (id của thực thể gốc: đợt khám, hóa đơn, tài khoản...), đúng docs; từ bước 8.1
 * mọi thực thể gốc đều có id UUID nên không còn dạng chuỗi tạm của quyết định D2.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OutboxEvent {

    /** Lùi dần khi gửi lỗi: 1s, 2s, 4s, ... tối đa 5 phút (Kiến trúc v4.0 mục 5.3). */
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "event_id", nullable = false, unique = true, columnDefinition = "binary(16)")
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "aggregate_id", nullable = false, columnDefinition = "binary(16)")
    private UUID aggregateId;

    /** Tên lớp sự kiện (không gồm package), ví dụ {@code VisitCompletedEvent}. */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "routing_key", nullable = false, length = 100)
    private String routingKey;

    /** JSON của sự kiện, đúng định dạng converter RabbitMQ sẽ đọc ở phía consumer. */
    @Column(name = "payload", nullable = false, columnDefinition = "json")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public void markPublished(Instant now) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
    }

    /**
     * Ghi nhận một lần gửi lỗi: tăng {@code attempt_count}, hẹn gửi lại sau thời gian lùi dần; đạt {@code maxAttempts}
     * thì chuyển {@code FAILED} (không tự gửi nữa).
     */
    public void markFailedAttempt(String error, Instant now, int maxAttempts) {
        this.attemptCount++;
        this.lastError = truncate(error);
        if (attemptCount >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
        } else {
            this.nextAttemptAt = now.plus(backoff(attemptCount));
        }
    }

    /** Khoảng chờ trước lần gửi thứ {@code attempt + 1}: 2^(attempt-1) giây, tối đa 5 phút. */
    static Duration backoff(int attempt) {
        if (attempt >= 20) {
            return MAX_BACKOFF;
        }
        Duration delay = Duration.ofSeconds(1L << Math.max(0, attempt - 1));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    private static String truncate(String error) {
        if (error == null) {
            return "Lỗi không rõ";
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
