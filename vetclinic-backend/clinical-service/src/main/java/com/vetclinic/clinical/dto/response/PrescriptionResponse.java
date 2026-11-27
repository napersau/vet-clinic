package com.vetclinic.clinical.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Đơn thuốc của một đợt khám. {@code medicineInvoiceId / medicineInvoiceStatus}: hóa đơn thuốc nháp do billing tạo khi chốt ca (null = chưa có hóa
 * đơn hoặc billing tạm không phản hồi). {@code stockWarnings} và {@code allergyWarning} chỉ có ở phản hồi lưu đơn: kê vượt tồn kho (EX-13) và thuốc
 * trùng tiền sử dị ứng của thú cưng; cả hai chỉ cảnh báo, không chặn lưu.
 */
public record PrescriptionResponse(
        UUID id,
        UUID visitId,
        String visitCode,
        String petName,
        String ownerName,
        String doctorName,
        String doctorNotes,
        Instant createdAt,
        UUID medicineInvoiceId,
        String medicineInvoiceStatus,
        List<PrescriptionItemResponse> items,
        List<String> stockWarnings,
        String allergyWarning
) {

    public PrescriptionResponse withInvoice(UUID invoiceId, String invoiceStatus) {
        return new PrescriptionResponse(id, visitId, visitCode, petName, ownerName, doctorName, doctorNotes, createdAt, invoiceId, invoiceStatus,
                items, stockWarnings, allergyWarning);
    }
}
