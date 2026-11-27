package com.vetclinic.billing.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Thêm / sửa nhóm thuốc (Admin). Khi sửa, {@code code} bỏ qua (bất biến); {@code active = false} để ẩn nhóm, không xóa. */
public record MedicineCategoryRequest(
        @NotBlank(message = "Mã nhóm không được để trống") @Size(max = 30, message = "Mã nhóm tối đa 30 ký tự") String code,
        @NotBlank(message = "Tên nhóm không được để trống") @Size(max = 100, message = "Tên nhóm tối đa 100 ký tự") String name,
        Boolean active
) {
}
