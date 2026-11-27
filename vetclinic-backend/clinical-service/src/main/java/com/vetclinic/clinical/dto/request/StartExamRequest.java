package com.vetclinic.clinical.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Bác sĩ bắt đầu khám (T02). Cả hai trường đều TÙY CHỌN: sinh hiệu lần đầu có thể gửi kèm hoặc thêm sau bằng
 * {@code POST .../vital-signs}; bệnh sử ghi vào hồ sơ khám (medical_records) vừa được tạo.
 */
public record StartExamRequest(
        @Valid VitalSignsRequest vitalSigns,
        @Size(max = 4000, message = "Bệnh sử tối đa 4000 ký tự") String anamnesis
) {
}
