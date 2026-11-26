package com.vetclinic.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * clinical-service -> notification-service: ca khám đã COMPLETED (thu đủ hóa đơn dịch vụ - T10, hoặc khách bỏ về - T08/T12).
 * CHỈ để thông báo (phiếu thu điện tử, bệnh án đã có trên Portal); không dùng để đổi trạng thái ở service khác.
 * reporting-service (bước 7.1) tính hiệu suất Bác sĩ theo ngày nên sự kiện mang Bác sĩ phụ trách lúc hoàn tất. Routing key {@code visit.completed}.
 */
public record VisitCompletedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,        // thu ngân (T10) hoặc người thao tác "Khách bỏ về"
        UUID visitId,
        String visitCode,
        UUID petId,
        boolean abandoned,       // true: khách bỏ về, hóa đơn dịch vụ còn lại giữ PENDING làm công nợ
        UUID doctorId,           // Bác sĩ phụ trách lúc hoàn tất (null nếu chưa phân công)
        String doctorName
) implements DomainEvent {

    /** Dạng cũ trước bước 7.1 (không có Bác sĩ). */
    public VisitCompletedEvent(UUID eventId, Instant occurredAt, UUID actorUserId, UUID visitId, String visitCode, UUID petId, boolean abandoned) {
        this(eventId, occurredAt, actorUserId, visitId, visitCode, petId, abandoned, null, null);
    }

    @Override
    public String routingKey() {
        return EventRouting.VISIT_COMPLETED;
    }
}
