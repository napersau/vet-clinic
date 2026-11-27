package com.vetclinic.clinical.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Thêm dịch vụ kèm giá khởi tạo (hiệu lực từ lúc tạo). Nhóm chọn theo mã; chính sách thu trước và hàng đợi do nhóm quyết định. */
public record CreateServiceRequest(
        @NotBlank(message = "Mã dịch vụ không được để trống") @Size(max = 50, message = "Mã tối đa 50 ký tự") String code,
        @NotBlank(message = "Tên dịch vụ không được để trống") @Size(max = 150, message = "Tên tối đa 150 ký tự") String name,
        @NotBlank(message = "Nhóm dịch vụ không được để trống") String categoryCode,
        @NotNull(message = "Đơn giá không được để trống") @DecimalMin(value = "0.00", message = "Đơn giá không được âm") @Digits(integer = 10, fraction = 2, message = "Đơn giá tối đa 10 chữ số nguyên và 2 chữ số thập phân") BigDecimal price,
        @DecimalMin(value = "0.00", message = "Thuế suất không được âm") @DecimalMax(value = "1.00", message = "Thuế suất tối đa 1.00 (100%)") @Digits(integer = 1, fraction = 2, message = "Thuế suất dạng 0.05 (5%)") BigDecimal vatRate,
        @Size(max = 30, message = "Đơn vị tối đa 30 ký tự") String unit,
        Short estimatedMinutes,
        @Size(max = 255, message = "Dặn dò chuẩn bị tối đa 255 ký tự") String preparationNotes,
        String description
) {
}
