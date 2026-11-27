package com.vetclinic.billing.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Sửa thông tin thuốc (Admin). Không có mã thuốc (bất biến), không có giá (đổi bằng POST /{id}/prices) và không có tồn kho (chỉ đổi qua nhập kho /
 * cân chỉnh). {@code ingredients} khác null = THAY toàn bộ danh sách thành phần; null = giữ nguyên. {@code active = false} là cách duy nhất để ngừng
 * kinh doanh, vì thuốc đã phát sinh giao dịch thì không xóa cứng. Trường tùy chọn bỏ trống = giữ nguyên.
 */
public record UpdateMedicineRequest(
        @Size(max = 30) String categoryCode,
        @NotBlank(message = "Tên thuốc không được để trống") @Size(max = 150) String name,
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
        Boolean active
) {
}
