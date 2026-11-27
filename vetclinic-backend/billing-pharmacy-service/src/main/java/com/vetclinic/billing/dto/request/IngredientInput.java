package com.vetclinic.billing.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Một thành phần hoạt chất (hàm lượng tùy chọn: vắc-xin ghi thành phần bằng chữ, không có số). */
public record IngredientInput(
        @NotBlank(message = "Tên hoạt chất không được để trống") @Size(max = 150, message = "Tên hoạt chất tối đa 150 ký tự") String ingredientName,
        @DecimalMin(value = "0.000", message = "Hàm lượng không âm") @Digits(integer = 7, fraction = 3, message = "Hàm lượng tối đa 7 chữ số nguyên và 3 chữ số thập phân") BigDecimal strengthValue,
        @Size(max = 20, message = "Đơn vị hàm lượng tối đa 20 ký tự") String strengthUnit
) {
}
