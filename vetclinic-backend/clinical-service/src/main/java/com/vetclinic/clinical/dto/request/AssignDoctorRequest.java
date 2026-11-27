package com.vetclinic.clinical.dto.request;

import java.util.UUID;
import jakarta.validation.constraints.NotNull;

public record AssignDoctorRequest(
        @NotNull(message = "Bác sĩ không được để trống") UUID doctorId
) {
}
