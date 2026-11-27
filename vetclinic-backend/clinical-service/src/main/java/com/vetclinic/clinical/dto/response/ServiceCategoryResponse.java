package com.vetclinic.clinical.dto.response;

/** Nhóm dịch vụ: {@code queueType} DOCTOR | LABORATORY | IMAGING | null (làm tại phòng). */
public record ServiceCategoryResponse(
        Short id,
        String code,
        String name,
        String queueType,
        boolean requiresPrepayment,
        short displayOrder,
        boolean active
) {
}
