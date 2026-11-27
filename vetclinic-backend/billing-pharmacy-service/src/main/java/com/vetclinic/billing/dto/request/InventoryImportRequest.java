package com.vetclinic.billing.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** FR-66: nhập kho qua {@code POST /pharmacy/medicines/{id}/import}. unitCost (giá nhập một đơn vị) tùy chọn, ghi vào nhật ký kho. */
public record InventoryImportRequest(
        @NotNull @Min(1) @Max(1_000_000) Integer quantity,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitCost,
        @Size(max = 255) String notes
) {
}
