package com.vetclinic.clinical.dto.response;

import java.util.UUID;
import com.vetclinic.clinical.constant.LabEvaluation;
import java.math.BigDecimal;
import java.time.Instant;

public record LabResultResponse(
        Long id,
        UUID serviceDetailId,
        Short labTestItemId,
        String code,
        String name,
        String unit,
        BigDecimal value,
        BigDecimal minValue,
        BigDecimal maxValue,
        LabEvaluation evaluation,
        UUID performedBy,
        String notes,
        Instant createdAt
) {
}
