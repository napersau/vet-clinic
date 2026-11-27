package com.vetclinic.clinical.dto.response;

import java.util.UUID;
import com.vetclinic.clinical.constant.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** Một hóa đơn dịch vụ kèm số tiền tính khi hiển thị (theo công thức của VIEW v_service_invoice_amounts, không lưu). */
public record ServiceInvoiceResponse(
        UUID id,
        String invoiceCode,
        UUID visitServiceDetailId,
        UUID serviceId,
        String serviceName,
        String category,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal vatRate,
        BigDecimal discountAmount,
        BigDecimal amountBeforeVat,
        BigDecimal vatAmount,
        BigDecimal payableAmount,
        PaymentStatus paymentStatus,
        UUID cashierId,
        Instant paidAt,
        boolean requiresPrepayment   // TRUE: thu được ngay (khám, XN, CĐHA) và thu xong thì phát phiếu; FALSE: thu khi chốt ca
) {
}
