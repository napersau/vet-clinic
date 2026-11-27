package com.vetclinic.clinical.dto.request;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * Khách có hẹn: gửi appointmentId (petId lấy từ lịch hẹn). Khách vãng lai: gửi petId.
 * doctorId tùy chọn: chọn Bác sĩ ngay khi tiếp nhận (hệ thống gợi ý Bác sĩ ít người chờ nhất) thì ca vào thẳng hàng đợi riêng của
 * Bác sĩ đó; không chọn thì ca vào danh sách chờ phân công. Lễ tân KHÔNG cân/đo (Bác sĩ làm trong phòng khám).
 * reasonForVisit: lý do đến khám do chủ nuôi khai (nhận cả tên cũ initialSymptoms). Trường cấp cứu nhận cả "isEmergency" (theo tài liệu) lẫn "emergency" (cũ).
 */
public record CheckInRequest(
        UUID petId,
        UUID appointmentId,
        @JsonAlias("initialSymptoms") @Size(max = 2000, message = "Lý do đến khám tối đa 2000 ký tự") String reasonForVisit,
        @JsonProperty("isEmergency") @JsonAlias("emergency") Boolean emergency,
        UUID doctorId
) {
}
