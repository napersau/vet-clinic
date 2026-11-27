package com.vetclinic.clinical.dto.response;

import java.util.UUID;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.TicketStatus;

import java.time.Instant;

/** Kết quả thao tác trên một phiếu xếp hàng (Gọi / Gọi số tiếp theo / Bắt đầu): phiếu + ca khám + số phòng của người gọi. */
public record QueueTicketResponse(
        UUID id,
        QueueType queueType,
        int ticketNo,
        int priority,
        TicketStatus status,
        Instant issuedAt,
        Instant lastCalledAt,
        UUID visitId,
        String visitCode,
        String petName,
        UUID doctorId,
        String roomNumber
) {
}
