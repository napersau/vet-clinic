package com.vetclinic.clinical.constant;

import java.util.List;

/** Khớp ENUM queue_tickets.status (Enum #12, SM-07). */
public enum TicketStatus {
    WAITING,
    IN_SERVICE,
    DONE,
    CANCELLED;

    /** Phiếu còn hiệu lực: đang chờ gọi hoặc đang được phục vụ. */
    public static final List<TicketStatus> ACTIVE = List.of(WAITING, IN_SERVICE);
}
