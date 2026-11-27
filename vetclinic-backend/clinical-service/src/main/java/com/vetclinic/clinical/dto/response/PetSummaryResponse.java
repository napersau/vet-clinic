package com.vetclinic.clinical.dto.response;

import java.util.UUID;

/**
 * Cho appointment-service (Feign {@code GET /internal/pets/{id}}): kiểm tra thú cưng tồn tại, thuộc đúng chủ nuôi và lấy tên để chụp
 * snapshot lên lịch hẹn. Thú cưng đã xóa mềm không trả về (404 PET_NOT_FOUND).
 */
public record PetSummaryResponse(
        UUID id,
        UUID ownerId,
        String name,
        String ownerName,
        String ownerPhone
) {
}
