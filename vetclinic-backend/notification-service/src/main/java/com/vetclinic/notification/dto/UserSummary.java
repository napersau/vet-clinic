package com.vetclinic.notification.dto;

import java.util.UUID;

/** Phần của auth-service UserSummaryResponse mà notification cần (tên và email để gửi thư). */
public record UserSummary(
        UUID id,
        String fullName,
        String email,
        String roomNumber
) {
}
