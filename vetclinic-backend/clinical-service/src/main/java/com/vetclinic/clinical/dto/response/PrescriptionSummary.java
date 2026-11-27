package com.vetclinic.clinical.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Đơn thuốc của một đợt khám trong bệnh án trọn đời (chỉ phần cần cho hướng dẫn dùng thuốc). Đơn không có giá và không có trạng thái
 * (chỉ định chuyên môn) nên xem được cả khi khách mua ngoài.
 */
public record PrescriptionSummary(
        UUID id,
        UUID visitId,
        String doctorNotes,
        List<Item> items
) {

    public record Item(
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
}
