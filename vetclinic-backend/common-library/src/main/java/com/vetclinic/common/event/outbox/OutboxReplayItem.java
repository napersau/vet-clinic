package com.vetclinic.common.event.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Một dòng outbox trả cho {@code GET /internal/outbox/events} (Task 4.5.4: dựng lại báo cáo). {@code payload} là JSON của sự kiện đúng như
 * đã gửi lên RabbitMQ; {@code id} là khóa tăng dần của dòng trong CSDL của service nguồn, dùng để phân trang và giữ thứ tự phát sinh.
 */
public record OutboxReplayItem(
        long id,
        UUID eventId,
        String eventType,
        String routingKey,
        String payload,
        Instant createdAt,
        String status
) {

    public static OutboxReplayItem of(OutboxEvent event) {
        return new OutboxReplayItem(event.getId(), event.getEventId(), event.getEventType(), event.getRoutingKey(), event.getPayload(),
                event.getCreatedAt(), event.getStatus().name());
    }
}
