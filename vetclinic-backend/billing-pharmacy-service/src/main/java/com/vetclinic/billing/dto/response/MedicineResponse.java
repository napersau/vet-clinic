package com.vetclinic.billing.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Thuốc kèm quy cách, thành phần, GIÁ HIỆN HÀNH (từ medicine_prices; null nếu chưa có giá đang áp dụng) và TỒN KHO (từ medicine_stocks).
 * lowStock = tồn kho chạm ngưỡng cảnh báo.
 */
public record MedicineResponse(
        UUID id,
        Short categoryId,
        String categoryCode,
        String categoryName,
        String code,
        String name,
        String dosageForm,
        String strength,
        String baseUnit,
        String packagingSpec,
        String manufacturer,
        String countryOfOrigin,
        String registrationNumber,
        String storageConditions,
        boolean requiresPrescription,
        List<IngredientResponse> ingredients,
        BigDecimal salePrice,
        BigDecimal vatRate,
        Instant priceEffectiveFrom,
        int stockQuantity,
        int minStockAlert,
        boolean lowStock,
        boolean active
) {
}
