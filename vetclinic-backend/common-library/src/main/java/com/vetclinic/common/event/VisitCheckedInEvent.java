package com.vetclinic.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * clinical-service -> notification-service, appointment-service.
 * appointment-service dùng sự kiện này để chuyển lịch hẹn sang CHECKED_IN (idempotent), vì
 * check-in không được ghi chéo sang DB của service khác trong cùng một transaction.
 * reporting-service (bước 7.1) dựng {@code visit_facts} từ sự kiện này nên payload mang đủ tên thú cưng / loài / chủ nuôi / Bác sĩ (snapshot lúc tiếp đón);
 * các trường thêm sau có thể null với tin nhắn phát trước khi mở rộng.
 */
public record VisitCheckedInEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,
        UUID visitId,
        String visitCode,
        UUID petId,
        UUID appointmentId,   // null nếu khách vãng lai (walk-in)
        boolean emergency,
        UUID doctorId,        // Bác sĩ được xếp ngay khi tiếp nhận (hàng đợi riêng của Bác sĩ); null = vào danh sách chờ phân công
        String petName,
        String speciesCode,
        UUID ownerId,
        String ownerName,
        String doctorName     // null khi chưa có Bác sĩ
) implements DomainEvent {

    /** Dạng cũ trước bước 7.1 (chưa có thông tin cho báo cáo); giữ để các nơi dựng sự kiện không cần thông tin đó vẫn biên dịch. */
    public VisitCheckedInEvent(UUID eventId, Instant occurredAt, UUID actorUserId, UUID visitId, String visitCode, UUID petId, UUID appointmentId,
                               boolean emergency, UUID doctorId) {
        this(eventId, occurredAt, actorUserId, visitId, visitCode, petId, appointmentId, emergency, doctorId, null, null, null, null, null);
    }

    @Override
    public String routingKey() {
        return EventRouting.VISIT_CHECKED_IN;
    }
}
