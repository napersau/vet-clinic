package com.vetclinic.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * clinical-service -> clinical-service (queue-issuer), reporting, notification: thu tiền một hay nhiều hóa đơn dịch vụ của một đợt khám. Ghi vào outbox
 * CÙNG giao dịch thu tiền nên đã thu tiền thì chắc chắn có phiếu, kể cả khi Redis hoặc service sập ngay sau commit (EX-18). Routing key
 * {@code invoice.service_paid}. Hàng đợi phát phiếu từ các dòng có {@code queueType} (khám, XN, CĐHA; gộp dịch vụ cùng phòng thành một phiếu);
 * báo cáo cộng doanh thu từ mọi dòng.
 */
public record ServiceInvoicePaidEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,        // thu ngân
        UUID visitId,
        String visitCode,
        UUID petId,
        String petName,
        String ownerName,
        UUID doctorId,           // Bác sĩ phụ trách lúc thu (null = chưa phân công)
        String doctorName,       // tên Bác sĩ lúc thu: báo cáo không phải chờ sự kiện tiếp đón để biết tên (các queue không bảo đảm thứ tự); null với tin nhắn cũ
        boolean emergency,
        UUID cashierId,
        Instant paidAt,          // mốc tính điểm hàng đợi (score) của các phiếu được phát
        List<Line> lines
) implements DomainEvent {

    /** Dạng cũ trước khi có {@code doctorName}. */
    public ServiceInvoicePaidEvent(UUID eventId, Instant occurredAt, UUID actorUserId, UUID visitId, String visitCode, UUID petId, String petName,
                                   String ownerName, UUID doctorId, boolean emergency, UUID cashierId, Instant paidAt, List<Line> lines) {
        this(eventId, occurredAt, actorUserId, visitId, visitCode, petId, petName, ownerName, doctorId, null, emergency, cashierId, paidAt, lines);
    }

    /** Một hóa đơn dịch vụ vừa thu. {@code queueType} = DOCTOR | LABORATORY | IMAGING; null = dịch vụ làm tại phòng (không có phiếu). */
    public record Line(
            UUID invoiceId,
            String invoiceCode,
            UUID visitServiceDetailId,
            UUID serviceId,
            String serviceCode,
            String serviceName,
            String category,
            String categoryName,     // tên nhóm dịch vụ (reporting ghi vào doanh thu theo nhóm; null với tin nhắn cũ)
            String queueType,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal vatRate,
            BigDecimal discountAmount,
            BigDecimal payableAmount
    ) {

        /** Dạng cũ trước bước 7.1 (chưa có tên nhóm). */
        public Line(UUID invoiceId, String invoiceCode, UUID visitServiceDetailId, UUID serviceId, String serviceCode, String serviceName, String category,
                    String queueType, int quantity, BigDecimal unitPrice, BigDecimal vatRate, BigDecimal discountAmount, BigDecimal payableAmount) {
            this(invoiceId, invoiceCode, visitServiceDetailId, serviceId, serviceCode, serviceName, category, null, queueType, quantity, unitPrice, vatRate,
                    discountAmount, payableAmount);
        }
    }

    @Override
    public String routingKey() {
        return EventRouting.INVOICE_SERVICE_PAID;
    }
}
