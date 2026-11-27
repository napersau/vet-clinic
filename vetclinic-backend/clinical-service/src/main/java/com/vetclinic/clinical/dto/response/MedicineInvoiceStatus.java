package com.vetclinic.clinical.dto.response;

import java.util.UUID;

/** Hóa đơn thuốc của một đơn do billing trả về ({@code status = null}: chưa có hóa đơn). Đã PAID thì không được sửa đơn (PHARM_1411). */
public record MedicineInvoiceStatus(UUID prescriptionId, UUID invoiceId, String status) {
}
