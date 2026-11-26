package com.vetclinic.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * auth-service -> (clinical, appointment, reporting): hồ sơ cá nhân của người dùng đổi (họ tên, số điện thoại, email, phòng).
 * Ghi vào outbox cùng giao dịch; consumer cập nhật các cột snapshot tên (bác sĩ, chủ nuôi). Routing key {@code user.profile_updated}.
 */
public record UserProfileUpdatedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,        // người sửa (chính chủ hoặc Admin / Lễ tân)
        UUID userId,
        String fullName,
        String phoneNumber,
        String email,
        String roomNumber        // phòng làm việc (null với khách hàng)
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.USER_PROFILE_UPDATED;
    }
}
