package com.vetclinic.clinical.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Hủy một chỉ định dịch vụ (EX-08). reasonType quyết định hóa đơn đã thu: CUSTOMER (khách tự hủy) -> giữ PAID, không hoàn;
 * CLINIC (phòng khám không thực hiện được: thiết bị hỏng, không lấy được mẫu, Bác sĩ hủy chỉ định) -> REFUNDED.
 * Bỏ trống: Bác sĩ hủy = CLINIC, kỹ thuật viên hủy = CUSTOMER. Phí khám không bao giờ hoàn (CLIN_1310).
 */
public record CancelServiceRequest(
        @NotBlank(message = "Lý do không được để trống") @Size(max = 500, message = "Lý do tối đa 500 ký tự") String reason,
        @Pattern(regexp = "CUSTOMER|CLINIC", message = "Loại lý do phải là CUSTOMER hoặc CLINIC") String reasonType
) {
}
