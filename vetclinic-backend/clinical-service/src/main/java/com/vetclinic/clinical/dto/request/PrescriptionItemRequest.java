package com.vetclinic.clinical.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record PrescriptionItemRequest(
        @NotNull UUID medicineId,
        @NotNull @Min(1) @Max(10_000) Integer quantity,
        @NotBlank @Size(max = 100) String dosage,
        @NotBlank @Size(max = 100) String frequency,
        @NotNull @Min(1) @Max(365) Integer durationDays,
        @Size(max = 2000) String instructions
) {
}
