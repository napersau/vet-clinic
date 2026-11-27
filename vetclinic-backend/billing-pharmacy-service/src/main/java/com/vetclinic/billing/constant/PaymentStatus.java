package com.vetclinic.billing.constant;

/** Khớp 100% với ENUM medicine_invoices.payment_status (SM-06); dùng chung giá trị với hóa đơn dịch vụ bên clinical-service. */
public enum PaymentStatus {
    PENDING,
    PAID,
    CANCELLED
}
