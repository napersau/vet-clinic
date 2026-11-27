package com.vetclinic.billing.dto.response;

import com.vetclinic.billing.constant.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Hóa đơn thuốc. Các số tiền KHÔNG lưu ở CSDL, được tính theo công thức VIEW v_medicine_invoice_amounts:
 * payableAmount = Σ(quantity × unitPrice × (1 + vatRate)) − discountAmount. Tên khách / SĐT / thú cưng / Bác sĩ là bản chụp từ sự kiện chốt ca.
 */
public record MedicineInvoiceResponse(
        UUID id,
        String invoiceCode,
        UUID prescriptionId,
        UUID visitId,
        String customerName,
        String customerPhone,
        String petName,
        String doctorName,
        BigDecimal amountBeforeVat,
        BigDecimal vatAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        PaymentStatus paymentStatus,
        UUID cashierId,
        Instant paidAt,
        Instant createdAt,
        List<MedicineInvoiceItemResponse> items
) {
}
