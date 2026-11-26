package com.vetclinic.common.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * auth-service -> (clinical, notification): trạng thái tài khoản đổi (khóa / mở khóa / ngừng sử dụng). Ghi vào outbox cùng giao dịch
 * với việc đổi trạng thái. clinical dùng để chuyển phiếu {@code WAITING} của Bác sĩ vừa bị khóa về danh sách chờ phân công;
 * notification báo Lễ tân phân công lại. Routing key {@code user.status_changed}.
 */
public record UserStatusChangedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,        // Admin thao tác
        UUID userId,             // tài khoản bị đổi trạng thái
        String fullName,
        String fromStatus,       // ACTIVE | LOCKED | INACTIVE
        String toStatus,
        List<String> roles       // mã vai trò của tài khoản tại thời điểm đổi (consumer chỉ quan tâm VETERINARIAN)
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.USER_STATUS_CHANGED;
    }
}
