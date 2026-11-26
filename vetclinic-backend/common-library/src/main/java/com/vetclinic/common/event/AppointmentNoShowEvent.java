package com.vetclinic.common.event;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * appointment-service -> notification-service: báo chủ nuôi lịch hẹn đã bị đánh dấu vắng mặt (NO_SHOW, EX-01).
 * Chỉ phát khi hệ thống tự quét ({@code actorUserId = null}) hoặc lễ tân đánh dấu thủ công.
 * Routing key {@code appointment.no_show}.
 */
public record AppointmentNoShowEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,        // null = hệ thống tự quét
        UUID appointmentId,
        String appointmentCode,
        UUID ownerId,
        UUID petId,
        LocalDate appointmentDate,
        LocalTime startTime
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.APPOINTMENT_NO_SHOW;
    }
}
