package com.vetclinic.billing.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Sửa hóa đơn thuốc nháp ({@code PUT /billing/medicine-invoices/{id}/items}): mỗi dòng gồm thuốc (có trong đơn) và số lượng khách mua;
 * {@code quantity = 0} là bỏ dòng khỏi hóa đơn; {@code discountAmount} bỏ trống = giữ nguyên mức giảm hiện tại. Không có API lập hóa đơn thuốc:
 * hóa đơn nháp tự có khi Bác sĩ chốt ca.
 */
public record MedicineInvoiceRequest(
        @NotEmpty @Size(max = 20) List<@Valid Line> items,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal discountAmount
) {
    public record Line(
            @NotNull UUID medicineId,
            @NotNull @Min(0) @Max(10_000) Integer quantity
    ) {
    }
}
