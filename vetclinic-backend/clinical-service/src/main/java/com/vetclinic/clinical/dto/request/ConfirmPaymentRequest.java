package com.vetclinic.clinical.dto.request;

import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Được thu một phần: chỉ các hóa đơn có id trong danh sách. Danh sách RỖNG chỉ hợp lệ khi ca đã chốt và không còn hóa đơn nào chờ thu
 * (phí khám, XN, CĐHA đã thu trước, không có dịch vụ tại phòng): thu ngân xác nhận để hoàn tất ca (T10) mà không thu thêm (AD-03).
 */
public record ConfirmPaymentRequest(
        @NotNull(message = "Thiếu danh sách hóa đơn")
        @Size(max = 100, message = "Tối đa 100 hóa đơn mỗi lần thu")
        List<@NotNull(message = "Mã hóa đơn không được để trống") UUID> invoiceIds
) {
}
