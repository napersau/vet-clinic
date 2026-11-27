package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.constant.PaymentStatus;
import com.vetclinic.clinical.constant.ServiceDetailStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Một dòng chỉ định dịch vụ. visit_service_details v3.0 KHÔNG chứa giá: unitPrice, vatRate là SNAPSHOT lấy từ hóa đơn dịch vụ
 * (service_invoices) tương ứng; subtotal = quantity × unitPrice (trước VAT, tính khi hiển thị, không lưu).
 * Số phải trả sau VAT và giảm giá nằm ở hóa đơn (bảng kê hóa đơn dịch vụ).
 */
public record ServiceDetailResponse(
        UUID id,
        UUID visitId,
        UUID serviceId,
        String serviceCode,
        String serviceName,
        String category,
        String queueType,   // hàng đợi của nhóm: DOCTOR | LABORATORY | IMAGING; null = dịch vụ làm tại phòng
        BigDecimal unitPrice,
        int quantity,
        BigDecimal subtotal,
        ServiceDetailStatus status,
        String notes,
        Instant createdAt,
        BigDecimal vatRate,
        UUID invoiceId,
        String invoiceCode,
        PaymentStatus paymentStatus
) {
}
