package com.vetclinic.clinical.dto.response;

import java.util.UUID;

/** Dòng thuốc trong đơn: chỉ định chuyên môn, không có giá; tên và đơn vị là bản chụp lúc kê. */
public record PrescriptionItemResponse(
        Long id,
        UUID medicineId,
        String medicineName,
        String unit,
        int quantity,
        String dosage,
        String frequency,
        int durationDays,
        String instructions
) {
}
