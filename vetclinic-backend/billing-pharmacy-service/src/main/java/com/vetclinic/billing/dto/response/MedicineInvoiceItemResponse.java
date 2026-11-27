package com.vetclinic.billing.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/** Dòng hóa đơn thuốc; unitPrice / vatRate là snapshot (làm mới lúc thu tiền, EX-20). */
public record MedicineInvoiceItemResponse(
        Long id,
        UUID medicineId,
        String medicineCode,
        String medicineName,
        String unit,
        int quantity,
        int prescribedQuantity,
        BigDecimal unitPrice,
        BigDecimal vatRate,
        BigDecimal amountBeforeVat,
        BigDecimal vatAmount,
        int stockQuantity,    // tồn kho hiện tại: số lượng mua tối đa khi hóa đơn còn chờ thu
        int minStockAlert
) {
}
