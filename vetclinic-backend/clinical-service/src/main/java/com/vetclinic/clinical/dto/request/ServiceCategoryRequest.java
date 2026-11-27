package com.vetclinic.clinical.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Tạo / sửa nhóm dịch vụ. {@code queueType}: DOCTOR | LABORATORY | IMAGING, bỏ trống = làm tại phòng (không xếp hàng riêng).
 * Mã nhóm bất biến sau khi tạo (khi sửa, mã gửi lên bị bỏ qua). Booleans để trống thì giữ nguyên (sửa) / mặc định (tạo).
 */
public record ServiceCategoryRequest(
        @NotBlank(message = "Mã nhóm không được để trống") @Size(max = 30, message = "Mã nhóm tối đa 30 ký tự") String code,
        @NotBlank(message = "Tên nhóm không được để trống") @Size(max = 100, message = "Tên nhóm tối đa 100 ký tự") String name,
        @Pattern(regexp = "^(DOCTOR|LABORATORY|IMAGING)?$", message = "Loại hàng đợi phải là DOCTOR, LABORATORY hoặc IMAGING") String queueType,
        Boolean requiresPrepayment,
        Short displayOrder,
        Boolean active
) {
}
