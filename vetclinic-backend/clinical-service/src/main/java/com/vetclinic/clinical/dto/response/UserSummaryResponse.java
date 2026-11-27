package com.vetclinic.clinical.dto.response;

import com.vetclinic.common.security.RoleCode;
import java.util.List;

import java.util.UUID;

public record UserSummaryResponse(
        UUID id,
        String fullName,
        String phoneNumber,
        RoleCode role,
        List<RoleCode> roles,
        String roomNumber,
        String status
) {
}
