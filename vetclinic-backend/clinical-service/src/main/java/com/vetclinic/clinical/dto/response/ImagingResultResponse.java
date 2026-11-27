package com.vetclinic.clinical.dto.response;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record ImagingResultResponse(
        UUID id,
        UUID serviceDetailId,
        String description,
        String conclusion,
        UUID performedBy,
        Instant performedAt,
        List<AttachmentResponse> attachments
) {
}
