package com.vetclinic.clinical.dto.response;

import java.util.List;

public record LabTestItemResponse(
        Short id,
        String code,
        String name,
        String unit,
        String description,
        boolean active,
        List<ReferenceRangeResponse> ranges
) {
}
