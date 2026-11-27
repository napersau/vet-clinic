package com.vetclinic.clinical.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** T04: chỉ định cận lâm sàng (chỉ nhận dịch vụ LABORATORY / IMAGING). {@code notes} áp cho các dòng chỉ định tạo ra. */
public record ParaclinicalOrderRequest(
        @NotEmpty(message = "Phải chọn ít nhất một dịch vụ") @Size(max = 20, message = "Tối đa 20 dịch vụ mỗi lần") List<UUID> serviceIds,
        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự") String notes
) {
}
