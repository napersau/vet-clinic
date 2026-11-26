package com.vetclinic.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * clinical-service -> reporting, notification: hóa đơn dịch vụ đã thu được hoàn tiền (chỉ XN / CĐHA khi phòng khám không thực hiện được; phí khám
 * không bao giờ hoàn). reporting ghi dòng REFUND (âm) vào doanh thu. Routing key {@code invoice.service_refunded}.
 */
public record ServiceInvoiceRefundedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID actorUserId,
        UUID visitId,
        String visitCode,
        UUID invoiceId,
        String invoiceCode,
        UUID visitServiceDetailId,
        UUID serviceId,
        String serviceName,
        String category,
        BigDecimal amount,       // số tiền hoàn (dương); báo cáo ghi thành dòng âm
        String reason
) implements DomainEvent {

    @Override
    public String routingKey() {
        return EventRouting.INVOICE_SERVICE_REFUNDED;
    }
}
