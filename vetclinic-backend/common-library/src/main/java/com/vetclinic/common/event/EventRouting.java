package com.vetclinic.common.event;

/**
 * Danh mục duy nhất về exchange / routing key (THIET_KE_KIEN_TRUC_MICROSERVICES.md §5.2).
 * Tên queue của consumer theo mẫu: &lt;service-name&gt;.&lt;routing-key&gt;, ví dụ "notification-service.visit.patient_called".
 */
public final class EventRouting {

    private EventRouting() {
    }

    public static final String EXCHANGE = "clinic.events";
    public static final String DEAD_LETTER_EXCHANGE = "clinic.events.dlx";
    public static final String DEAD_LETTER_SUFFIX = ".dlq";

    // ===== Danh mục chuẩn v3.0 (THIET_KE_KIEN_TRUC_MICROSERVICES.md mục 5.2) =====
    public static final String APPOINTMENT_BOOKED = "appointment.booked";
    public static final String APPOINTMENT_STATUS_CHANGED = "appointment.status_changed";
    public static final String APPOINTMENT_NO_SHOW = "appointment.no_show";
    public static final String VISIT_CHECKED_IN = "visit.checked_in";
    public static final String VISIT_PATIENT_CALLED = "visit.patient_called";
    public static final String QUEUE_TICKET_ISSUED = "queue.ticket_issued";   // thêm 17/11/2026: thu tiền xong -> phát phiếu xếp hàng
    public static final String VISIT_RESULTS_READY = "visit.results_ready";
    public static final String VISIT_COMPLETED = "visit.completed";
    public static final String PHARMACY_MEDICINE_INVOICE_PAID = "pharmacy.medicine_invoice_paid";
    public static final String PHARMACY_LOW_STOCK = "pharmacy.low_stock";
    // Thêm 21/11/2026 (v4.0, bước 2.2): auth phát khi hồ sơ / trạng thái tài khoản đổi
    public static final String USER_PROFILE_UPDATED = "user.profile_updated";
    public static final String USER_STATUS_CHANGED = "user.status_changed";
    public static final String PET_UPDATED = "pet.updated";
    // Thêm 21/11/2026 (v4.0, bước 5.2): chốt ca có đơn thuốc -> billing tạo hóa đơn thuốc nháp
    public static final String PRESCRIPTION_CREATED = "prescription.created";
    // Thêm 21/11/2026 (v4.0, bước 6.2): thu tiền / hoàn tiền dịch vụ -> phát phiếu xếp hàng, báo cáo; hủy ca
    public static final String INVOICE_SERVICE_PAID = "invoice.service_paid";
    public static final String INVOICE_SERVICE_REFUNDED = "invoice.service_refunded";
    public static final String VISIT_CANCELLED = "visit.cancelled";

    public static String queueName(String serviceName, String routingKey) {
        return serviceName + "." + routingKey;
    }
}
