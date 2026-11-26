package com.vetclinic.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * clinical-service -> reporting, notification: đợt khám bị hủy (T03 hủy tiếp nhận, T11 hủy ca / chuyển viện). reporting tăng {@code cancelled_visits};
 * notification báo các màn hình liên quan. Routing key {@code visit.cancelled}.
 */
public record VisitCancelledEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,
        UUID visitId,
        String visitCode,
        UUID petId,
        UUID doctorId,           // null nếu chưa phân công
        String reason
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.VISIT_CANCELLED;
    }
}
