package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.entity.VitalSign;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Một lần đo sinh hiệu. */
public record VitalSignResponse(
        Long id,
        UUID visitId,
        BigDecimal weightKg,
        BigDecimal temperatureC,
        Integer heartRateBpm,
        Integer respiratoryRateBpm,
        Integer bodyConditionScore,
        String mucousMembraneColor,
        BigDecimal capillaryRefillSec,
        String notes,
        UUID recordedBy,
        Instant recordedAt
) {
    public static VitalSignResponse of(VitalSign s) {
        return new VitalSignResponse(s.getId(), s.getVisitId(), s.getWeightKg(), s.getTemperatureC(), s.getHeartRateBpm(),
                s.getRespiratoryRateBpm(), s.getBodyConditionScore(), s.getMucousMembraneColor(), s.getCapillaryRefillSec(),
                s.getNotes(), s.getRecordedBy(), s.getRecordedAt());
    }
}
