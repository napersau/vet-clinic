package com.vetclinic.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * clinical-service -> notification-service: ca khám đã có đủ kết quả cận lâm sàng (T06, WAITING_RESULTS -> RESULTS_READY).
 * Ca tự lên ĐẦU hàng đợi của Bác sĩ khám chính (chỉ sau ca cấp cứu) - không còn "Khóa ưu tiên".
 * Routing key {@code visit.results_ready}. Thay DoctorPriorityCallbackEvent.
 */
public record ParaclinicalResultsReadyEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,        // luôn null: hệ thống tự chuyển trạng thái
        UUID visitId,
        String visitCode,
        UUID doctorId,
        UUID petId
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.VISIT_RESULTS_READY;
    }
}
