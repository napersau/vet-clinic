package com.vetclinic.clinical.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Lưu CẢ đơn thuốc (UC-31): thay toàn bộ danh sách thuốc; lần đầu tạo đơn, các lần sau cập nhật cùng đơn. */
public record PrescriptionRequest(
        @Size(max = 4000) String doctorNotes,
        @NotEmpty @Size(max = 20) List<@Valid PrescriptionItemRequest> items
) {
}
