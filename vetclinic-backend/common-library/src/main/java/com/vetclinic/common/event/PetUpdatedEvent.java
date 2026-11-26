package com.vetclinic.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * clinical-service -> (appointment, reporting): tên thú cưng hoặc chủ nuôi của thú cưng đổi. Ghi vào outbox cùng giao dịch với việc sửa hồ sơ;
 * consumer cập nhật các cột snapshot ({@code appointments.pet_name_snapshot}, ...). Routing key {@code pet.updated}.
 */
public record PetUpdatedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,        // người sửa (chủ nuôi hoặc Lễ tân)
        UUID petId,
        String petName,
        UUID ownerId,
        String ownerName,
        String ownerPhone
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.PET_UPDATED;
    }
}
