package com.vetclinic.clinical.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Sửa thông tin dịch vụ (không chứa giá: đổi giá bằng {@code POST /api/v1/catalog/services/{id}/prices}). Trường null thì giữ nguyên.
 * Ngừng cung cấp bằng {@code active = false}, không xóa (hóa đơn cũ vẫn tra được).
 */
public record UpdateServiceRequest(
        @Size(min = 1, max = 150, message = "Tên từ 1 đến 150 ký tự") String name,
        @Size(min = 1, max = 30, message = "Đơn vị từ 1 đến 30 ký tự") String unit,
        Short estimatedMinutes,
        @Size(max = 255, message = "Dặn dò chuẩn bị tối đa 255 ký tự") String preparationNotes,
        String description,
        Boolean active
) {
}
