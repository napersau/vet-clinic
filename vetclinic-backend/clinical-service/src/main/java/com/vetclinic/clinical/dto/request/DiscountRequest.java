package com.vetclinic.clinical.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record DiscountRequest(
        @NotNull(message = "Số tiền giảm giá không được để trống")
        @DecimalMin(value = "0.00", message = "Số tiền giảm giá không được âm")
        @Digits(integer = 10, fraction = 2, message = "Số tiền giảm giá tối đa 10 chữ số nguyên và 2 chữ số thập phân")
        BigDecimal discountAmount
) {
}
