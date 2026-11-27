package com.vetclinic.clinical.dto.response;

import java.util.UUID;
import com.vetclinic.clinical.constant.DoctorStatus;

public record DoctorStatusResponse(
        UUID doctorId,
        String fullName,
        String roomNumber,
        DoctorStatus status,
        int waitingCount
) {
}
