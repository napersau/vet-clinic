package com.vetclinic.notification.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Nội dung đẩy xuống client (STOMP) và trả về từ GET /api/v1/notifications.
 * data chứa các id để frontend điều hướng (visitId, appointmentId, medicineId...).
 * read: đã đọc hay chưa theo từng người dùng (POST /notifications/{id}/read); thông báo vừa đẩy qua WebSocket luôn là false.
 */
public record NotificationMessage(
        UUID id,
        NotificationType type,
        Severity severity,
        String title,
        String body,
        Map<String, Object> data,
        Instant createdAt,
        boolean read
) {

    public static NotificationMessage of(NotificationType type, Severity severity, String title, String body,
                                         Map<String, Object> data, Instant now) {
        return new NotificationMessage(UUID.randomUUID(), type, severity, title, body, data, now, false);
    }

    public NotificationMessage withRead(boolean value) {
        return value == read ? this : new NotificationMessage(id, type, severity, title, body, data, createdAt, value);
    }
}
