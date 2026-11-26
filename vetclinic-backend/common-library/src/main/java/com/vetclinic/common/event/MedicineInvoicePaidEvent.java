package com.vetclinic.common.event;

import java.math.BigDecimal;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

/**
 * billing-pharmacy-service -> notification-service: hóa đơn thuốc đã PAID (đồng thời đã trừ kho trong cùng giao dịch).
 * KHÔNG làm thay đổi trạng thái đợt khám. Routing key {@code pharmacy.medicine_invoice_paid}.
 * reporting-service (bước 7.1) ghi doanh thu thuốc theo từng dòng nên sự kiện mang các dòng thuốc (nhóm thuốc, số lượng, giá + VAT đã chốt lúc thu) và
 * giảm giá của hóa đơn; các trường thêm sau có thể null với tin nhắn phát trước khi mở rộng.
 */
public record MedicineInvoicePaidEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,        // thu ngân
        UUID invoiceId,
        String invoiceCode,
        UUID prescriptionId,
        UUID visitId,
        BigDecimal payableAmount, // số phải trả sau VAT và giảm giá (tính từ các dòng, không lưu ở CSDL)
        BigDecimal discountAmount, // giảm giá của cả hóa đơn
        Instant paidAt,
        List<Line> lines
) implements DomainEvent {

    /** Một dòng thuốc đã bán. {@code unitPrice} / {@code vatRate} là giá trị đã làm mới theo bảng giá hiện hành lúc thu (EX-20). */
    public record Line(
            UUID medicineId,
            String medicineCode,
            String medicineName,
            String categoryCode,
            String categoryName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal vatRate
    ) {
    }

    /** Dạng cũ trước bước 7.1 (chưa có dòng thuốc). */
    public MedicineInvoicePaidEvent(UUID eventId, Instant occurredAt, UUID actorUserId, UUID invoiceId, String invoiceCode, UUID prescriptionId, UUID visitId,
                                    BigDecimal payableAmount) {
        this(eventId, occurredAt, actorUserId, invoiceId, invoiceCode, prescriptionId, visitId, payableAmount, null, null, null);
    }

    @Override
    public String routingKey() {
        return EventRouting.PHARMACY_MEDICINE_INVOICE_PAID;
    }
}
