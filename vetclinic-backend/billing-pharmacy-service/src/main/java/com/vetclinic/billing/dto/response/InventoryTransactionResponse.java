package com.vetclinic.billing.dto.response;

import java.util.UUID;
import com.vetclinic.billing.constant.InventoryTransactionType;

import java.math.BigDecimal;
import java.time.Instant;

/** quantity có dấu (+ nhập / - xuất). Tồn sau giao dịch không lưu: nếu cần thì cộng dồn quantity theo thứ tự. */
public record InventoryTransactionResponse(
        Long id,
        UUID medicineId,
        InventoryTransactionType transactionType,
        int quantity,
        BigDecimal unitCost,
        UUID medicineInvoiceId,
        String notes,
        UUID createdBy,
        Instant createdAt
) {
}
