package com.vetclinic.billing.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Đặt giá bán mới cho thuốc (đóng dòng giá đang mở + thêm dòng mới). {@code effectiveFrom} phải sau thời điểm bắt đầu của giá đang mở
 * ({@code BILL_1410}); bỏ trống = áp dụng ngay. Hóa đơn đã lập giữ giá cũ (EX-20).
 */
public record ChangePriceRequest(
        @NotNull(message = "Giá bán không được để trống") @DecimalMin(value = "0.00", message = "Giá bán không được âm") @Digits(integer = 10, fraction = 2, message = "Giá bán tối đa 10 chữ số nguyên và 2 chữ số thập phân") BigDecimal salePrice,
        @NotNull(message = "Thuế suất không được để trống") @DecimalMin(value = "0.00", message = "Thuế suất không được âm") @DecimalMax(value = "1.00", message = "Thuế suất tối đa 1.00 (100%)") @Digits(integer = 1, fraction = 2, message = "Thuế suất dạng 0.05 (5%)") BigDecimal vatRate,
        Instant effectiveFrom,
        @Size(max = 255, message = "Lý do tối đa 255 ký tự") String reason
) {
}
