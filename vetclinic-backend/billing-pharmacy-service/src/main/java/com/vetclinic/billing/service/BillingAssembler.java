package com.vetclinic.billing.service;

import com.vetclinic.billing.dto.response.InventoryTransactionResponse;
import com.vetclinic.billing.dto.response.MedicineInvoiceItemResponse;
import com.vetclinic.billing.dto.response.MedicineInvoiceResponse;
import com.vetclinic.billing.entity.InventoryTransaction;
import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.billing.entity.MedicineInvoice;
import com.vetclinic.billing.entity.MedicineInvoiceItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** Entity -> DTO. Phải gọi bên trong transaction (đọc collection lười: dòng hóa đơn, thuốc, tồn kho). */
@Component
public class BillingAssembler {

    // ------------------------------------------------------------------ hóa đơn thuốc

    /** Tiền được tính từ các dòng (không lưu): xem {@link MedicineInvoiceAmounts}. */
    public MedicineInvoiceResponse toResponse(MedicineInvoice invoice) {
        List<MedicineInvoiceItemResponse> items = invoice.getItems().stream().map(this::toResponse).toList();
        return new MedicineInvoiceResponse(invoice.getId(), invoice.getInvoiceCode(), invoice.getPrescriptionId(), invoice.getVisitId(),
                invoice.getCustomerNameSnapshot(), invoice.getCustomerPhoneSnapshot(), invoice.getPetNameSnapshot(), invoice.getDoctorNameSnapshot(),
                MedicineInvoiceAmounts.beforeVat(invoice.getItems()), MedicineInvoiceAmounts.vat(invoice.getItems()),
                invoice.getDiscountAmount(), MedicineInvoiceAmounts.payable(invoice.getItems(), invoice.getDiscountAmount()),
                invoice.getPaymentStatus(), invoice.getCashierId(), invoice.getPaidAt(), invoice.getCreatedAt(), items);
    }

    private MedicineInvoiceItemResponse toResponse(MedicineInvoiceItem item) {
        Medicine medicine = item.getMedicine();
        BigDecimal base = item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new MedicineInvoiceItemResponse(item.getId(), medicine.getId(), medicine.getCode(), medicine.getName(), medicine.getUnit(),
                item.getQuantity(), item.getPrescribedQuantity(), item.getUnitPrice(), item.getVatRate(), MedicineInvoiceAmounts.money(base),
                MedicineInvoiceAmounts.money(base.multiply(item.getVatRate())), medicine.getStockQuantity(), medicine.getMinStockAlert());
    }

    // ------------------------------------------------------------------ kho

    public InventoryTransactionResponse toResponse(InventoryTransaction t) {
        return new InventoryTransactionResponse(t.getId(), t.getMedicineId(), t.getTransactionType(), t.getQuantity(), t.getUnitCost(),
                t.getMedicineInvoiceId(), t.getNotes(), t.getCreatedBy(), t.getCreatedAt());
    }
}
