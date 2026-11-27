package com.vetclinic.clinical.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Dịch vụ kèm GIÁ ĐANG ÁP DỤNG (từ {@code service_prices}); {@code price} / {@code vatRate} là null nếu dịch vụ chưa có giá hiệu lực.
 * {@code category} là mã nhóm; {@code queueType} và {@code requiresPrepayment} đọc từ nhóm.
 */
public record ServiceResponse(
        UUID id,
        String code,
        String name,
        Short categoryId,
        String category,
        String categoryName,
        String queueType,
        boolean requiresPrepayment,
        String unit,
        Short estimatedMinutes,
        String preparationNotes,
        String description,
        boolean active,
        BigDecimal price,
        BigDecimal vatRate,
        Instant priceEffectiveFrom
) {
}
