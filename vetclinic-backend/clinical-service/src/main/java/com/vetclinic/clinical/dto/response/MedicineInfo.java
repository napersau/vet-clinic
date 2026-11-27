package com.vetclinic.clinical.dto.response;

import java.util.List;
import java.util.UUID;

/** Thuốc do billing-pharmacy-service trả về cho clinical lúc kê đơn (tên, đơn vị, tồn kho để cảnh báo, hoạt chất để cảnh báo dị ứng). */
public record MedicineInfo(
        UUID id,
        String code,
        String name,
        String baseUnit,
        int stockQuantity,
        int minStockAlert,
        boolean active,
        List<Ingredient> ingredients
) {

    public record Ingredient(String ingredientName) {
    }
}
