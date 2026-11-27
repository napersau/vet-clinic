package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.TicketStatus;

import java.time.Instant;
import java.util.UUID;

/** Phiếu xếp hàng gắn kèm một ca trên màn hình hàng đợi (số in trên phiếu, mức ưu tiên, giờ gọi gần nhất, số lần đã gọi). Id phiếu là UUID (chuỗi trong JSON). */
public record TicketInfo(
        UUID id,
        QueueType queueType,
        int ticketNo,
        int priority,
        TicketStatus status,
        Instant issuedAt,
        Instant lastCalledAt,
        int calledCount
) {
}
