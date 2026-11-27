package com.vetclinic.clinical.dto.response;

import java.util.UUID;
import com.vetclinic.clinical.constant.VisitStatus;
import java.time.Instant;

public record VisitHistoryResponse(
        Long id,
        VisitStatus fromStatus,
        VisitStatus toStatus,
        UUID changedByUserId,
        String notes,
        Instant createdAt
) {
}
