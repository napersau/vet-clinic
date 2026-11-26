package com.vetclinic.appointment.dto.response;

import java.util.UUID;

/** Phần của clinical-service {@code PetSummaryResponse} mà appointment cần: kiểm tra chủ sở hữu và chụp snapshot lên lịch hẹn. */
public record PetSummaryResponse(UUID id, UUID ownerId, String name, String ownerName, String ownerPhone) {
}
