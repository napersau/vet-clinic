package com.vetclinic.billing.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Một dòng lịch sử giá. {@code current} = đang hiệu lực ngay bây giờ; {@code effectiveTo} null = dòng mở. */
public record MedicinePriceResponse(
        Long id,
        BigDecimal salePrice,
        BigDecimal vatRate,
        Instant effectiveFrom,
        Instant effectiveTo,
        String changeReason,
        UUID createdBy,
        Instant createdAt,
        boolean current
) {
}
