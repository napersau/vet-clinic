package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.constant.PetGender;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PetResponse(
        UUID id,
        UUID ownerId,
        String ownerName,
        String ownerPhone,
        Short speciesId,
        String speciesCode,
        String speciesName,
        String petCode,
        String name,
        String breed,
        PetGender gender,
        LocalDate dateOfBirth,
        String color,
        BigDecimal weight,
        boolean neutered,
        String microchipNumber,
        String identifyingCharacteristics,
        String allergies,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Cân nặng hiện tại không lưu ở hồ sơ thú cưng: lấy từ đợt khám gần nhất (PetServiceImpl gắn vào đây), null nếu chưa từng được cân.
     */
    public PetResponse withWeight(BigDecimal weight) {
        return new PetResponse(id, ownerId, ownerName, ownerPhone, speciesId, speciesCode, speciesName, petCode, name, breed, gender,
                dateOfBirth, color, weight, neutered, microchipNumber, identifyingCharacteristics, allergies, notes, createdAt, updatedAt);
    }
}
