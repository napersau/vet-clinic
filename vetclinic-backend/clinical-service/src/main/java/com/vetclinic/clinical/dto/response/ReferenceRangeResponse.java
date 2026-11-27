package com.vetclinic.clinical.dto.response;

import java.math.BigDecimal;

public record ReferenceRangeResponse(
        Short speciesId,
        String speciesCode,
        BigDecimal minValue,
        BigDecimal maxValue
) {
}
