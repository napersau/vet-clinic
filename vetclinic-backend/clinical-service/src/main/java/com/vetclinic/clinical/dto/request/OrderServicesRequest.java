package com.vetclinic.clinical.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Chỉ định DỊCH VỤ TẠI PHÒNG (tiêm, tẩy giun, thủ thuật, phẫu thuật...): mỗi dịch vụ một hóa đơn PENDING, ca vẫn IN_PROGRESS. */
public record OrderServicesRequest(
        @NotEmpty(message = "Phải chọn ít nhất một dịch vụ") @Size(max = 20, message = "Tối đa 20 dịch vụ mỗi lần") List<@Valid ServiceOrderItem> services
) {
}
