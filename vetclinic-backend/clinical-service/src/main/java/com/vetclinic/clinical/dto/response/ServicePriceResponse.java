package com.vetclinic.clinical.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/** Một dòng lịch sử giá. {@code current} = đang hiệu lực ngay bây giờ; {@code effectiveTo} null = dòng mở. */
public record ServicePriceResponse(
        Long id,
        BigDecimal price,
        BigDecimal vatRate,
        Instant effectiveFrom,
        Instant effectiveTo,
        String changeReason,
        java.util.UUID createdBy,
        Instant createdAt,
        boolean current
) {
}
