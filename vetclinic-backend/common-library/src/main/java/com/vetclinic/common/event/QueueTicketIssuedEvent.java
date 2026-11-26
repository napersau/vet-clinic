package com.vetclinic.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * clinical-service -> notification-service: hệ thống vừa phát một phiếu xếp hàng (SM-07, Q1) vì khách đã nộp phí khám / tiền
 * Xét nghiệm, CĐHA, hoặc vì ca cấp cứu (priority 2), ca có đủ kết quả cận lâm sàng (priority 1). Routing key
 * {@code queue.ticket_issued}. Dùng để làm mới hàng đợi Bác sĩ / phòng Lab / phòng CĐHA và báo số phiếu cho chủ nuôi.
 */
public record QueueTicketIssuedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,        // thu ngân phát phiếu; null = hệ thống tự phát
        UUID ticketId,           // UUIDv7 sinh lúc phát phiếu trên Redis
        String queueType,        // DOCTOR | LABORATORY | IMAGING
        int ticketNo,
        UUID visitId,
        String visitCode,
        UUID petId,
        UUID doctorId,           // chỉ phiếu DOCTOR; null = danh sách chờ phân công
        int priority             // 2 cấp cứu, 1 ca có kết quả CLS, 0 thường
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.QUEUE_TICKET_ISSUED;
    }
}
