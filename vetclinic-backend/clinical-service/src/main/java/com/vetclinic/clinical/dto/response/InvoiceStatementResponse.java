package com.vetclinic.clinical.dto.response;

import java.util.UUID;
import com.vetclinic.clinical.constant.VisitStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bảng kê hóa đơn dịch vụ của một đợt khám (UC-34). totalPending = tổng cần thu (các hóa đơn PENDING); totalPaid = đã thu;
 * totalRefunded = đã hoàn (phòng khám không làm được dịch vụ đã thu); hóa đơn CANCELLED không tính vào tổng nào.
 * tickets = phiếu xếp hàng còn hiệu lực của ca (thu phí khám / tiền CLS xong thì có phiếu mới để in số thứ tự).
 */
public record InvoiceStatementResponse(
        UUID visitId,
        String visitCode,
        VisitStatus visitStatus,
        List<ServiceInvoiceResponse> invoices,
        BigDecimal totalPending,
        BigDecimal totalPaid,
        BigDecimal totalRefunded,
        List<TicketInfo> tickets
) {
}
