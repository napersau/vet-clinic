package com.vetclinic.billing.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Thêm thuốc vào danh mục (Admin): thông tin + quy cách + thành phần + giá bán đầu tiên (ghi vào lịch sử giá) + tồn kho ban đầu (ghi nhật ký nhập
 * kho). minStockAlert, requiresPrescription, vatRate (mặc định 0.05), initialStock bỏ trống được.
 */
public record MedicineRequest(
        @NotBlank(message = "Nhóm thuốc không được để trống") @Size(max = 30) String categoryCode,
        @NotBlank(message = "Mã thuốc không được để trống") @Size(max = 50, message = "Mã thuốc tối đa 50 ký tự") String code,
        @NotBlank(message = "Tên thuốc không được để trống") @Size(max = 150, message = "Tên thuốc tối đa 150 ký tự") String name,
        @NotBlank(message = "Dạng bào chế không được để trống") @Size(max = 50) String dosageForm,
        @Size(max = 50) String strength,
        @NotBlank(message = "Đơn vị bán nhỏ nhất không được để trống") @Size(max = 30) String baseUnit,
        @NotBlank(message = "Quy cách đóng gói không được để trống") @Size(max = 150) String packagingSpec,
        @Size(max = 150) String manufacturer,
        @Size(max = 100) String countryOfOrigin,
        @Size(max = 50) String registrationNumber,
        @Size(max = 255) String storageConditions,
        Boolean requiresPrescription,
        @Min(0) @Max(1_000_000) Integer minStockAlert,
        @Valid @Size(max = 20, message = "Tối đa 20 hoạt chất") List<IngredientInput> ingredients,
        @NotNull(message = "Giá bán không được để trống") @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal salePrice,
        @DecimalMin("0.00") @DecimalMax("1.00") @Digits(integer = 1, fraction = 2) BigDecimal vatRate,
        @Min(0) @Max(1_000_000) Integer initialStock
) {
}
