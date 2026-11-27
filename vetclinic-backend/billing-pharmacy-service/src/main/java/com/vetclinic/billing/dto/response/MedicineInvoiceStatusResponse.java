package com.vetclinic.billing.dto.response;

import com.vetclinic.billing.constant.PaymentStatus;

import java.util.UUID;

/** Cho clinical (T14): đơn thuốc đã có hóa đơn thuốc chưa và ở trạng thái nào ({@code status = null}: chưa có hóa đơn). */
public record MedicineInvoiceStatusResponse(UUID prescriptionId, UUID invoiceId, PaymentStatus status) {
}
