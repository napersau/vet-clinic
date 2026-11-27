package com.vetclinic.notification.model;

/**
 * Loại thông báo để frontend chọn biểu tượng / hành vi (popup, chuông, làm mới hàng đợi...). Khớp 11 sự kiện (Kiến trúc §5.2).
 * v3.0 bỏ APPOINTMENT_CREATED / PRIORITY_CALLBACK / PAYMENT_PENDING (v2).
 */
public enum NotificationType {
    APPOINTMENT_BOOKED,      // appointment.booked
    APPOINTMENT_UPDATED,     // appointment.status_changed (duyệt / hủy / dời giờ)
    APPOINTMENT_NO_SHOW,     // appointment.no_show
    VISIT_CHECKED_IN,        // visit.checked_in
    PATIENT_CALLED,          // visit.patient_called (mời số phiếu vào phòng / gọi lại)
    RESULTS_READY,           // visit.results_ready
    VISIT_COMPLETED,         // visit.completed
    MEDICINE_INVOICE_PAID,   // pharmacy.medicine_invoice_paid
    LOW_STOCK,               // pharmacy.low_stock
    TICKET_ISSUED,           // queue.ticket_issued (đã nộp tiền, phát phiếu xếp hàng)
    USER_STATUS_CHANGED      // user.status_changed (Bác sĩ bị khóa / ngừng / kích hoạt lại: Lễ tân phân công lại)
}
