package com.vetclinic.clinical.constant;

/**
 * Khớp ENUM service_invoices.payment_status (SM-05). REFUNDED (17/11/2026): phòng khám không thực hiện được dịch vụ XN / CĐHA
 * đã thu (thiết bị hỏng, không lấy được mẫu, Bác sĩ hủy chỉ định trước khi làm). Phí khám không hoàn; khách tự hủy không hoàn.
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    CANCELLED,
    REFUNDED
}
