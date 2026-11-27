package com.vetclinic.notification.dto;

import java.util.UUID;

/** Phần của clinical-service VisitSummaryResponse mà notification cần để nhắm đúng người nhận và ghi tên thú cưng. */
public record VisitSummary(
        UUID id,
        String visitCode,
        UUID petId,
        String petName,
        UUID ownerId,
        UUID doctorId
) {
}
