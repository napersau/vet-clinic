package com.vetclinic.billing.service;

import com.vetclinic.billing.entity.MedicineInvoiceItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Tiền hóa đơn thuốc KHÔNG lưu (quy chuẩn): tính đúng công thức của VIEW {@code v_medicine_invoice_amounts}, làm tròn HALF_UP 2 chữ số.
 * <pre>
 *   amount_before_vat = ROUND(SUM(quantity * unit_price), 2)
 *   vat_amount        = ROUND(SUM(quantity * unit_price * vat_rate), 2)
 *   payable_amount    = ROUND(SUM(quantity * unit_price * (1 + vat_rate)) - discount_amount, 2)
 * </pre>
 * Mỗi dòng có thuế suất riêng (snapshot) nên phải cộng từng dòng rồi mới làm tròn, không tính trên tổng.
 */
public final class MedicineInvoiceAmounts {

    private MedicineInvoiceAmounts() {
    }

    public static BigDecimal beforeVat(Collection<MedicineInvoiceItem> items) {
        BigDecimal sum = BigDecimal.ZERO;
        for (MedicineInvoiceItem item : items) {
            sum = sum.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        return money(sum);
    }

    public static BigDecimal vat(Collection<MedicineInvoiceItem> items) {
        BigDecimal sum = BigDecimal.ZERO;
        for (MedicineInvoiceItem item : items) {
            sum = sum.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())).multiply(item.getVatRate()));
        }
        return money(sum);
    }

    /** SUM(quantity * unit_price * (1 + vat_rate)) CHƯA làm tròn: trần của giảm giá (BILL_1402, EX-14). */
    public static BigDecimal gross(Collection<MedicineInvoiceItem> items) {
        BigDecimal sum = BigDecimal.ZERO;
        for (MedicineInvoiceItem item : items) {
            sum = sum.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                    .multiply(BigDecimal.ONE.add(item.getVatRate())));
        }
        return sum;
    }

    public static BigDecimal payable(Collection<MedicineInvoiceItem> items, BigDecimal discount) {
        return money(gross(items).subtract(discount));
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
