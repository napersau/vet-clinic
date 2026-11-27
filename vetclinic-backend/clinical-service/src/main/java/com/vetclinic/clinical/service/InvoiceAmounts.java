package com.vetclinic.clinical.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tiền hóa đơn dịch vụ KHÔNG lưu (quy chuẩn): tính theo đúng công thức của VIEW {@code v_service_invoice_amounts}, làm tròn HALF_UP 2 chữ số.
 * <pre>
 *   amount_before_vat = ROUND(quantity * unit_price, 2)
 *   vat_amount        = ROUND(quantity * unit_price * vat_rate, 2)
 *   payable_amount    = ROUND(quantity * unit_price * (1 + vat_rate) - discount_amount, 2)
 * </pre>
 */
public final class InvoiceAmounts {

    private InvoiceAmounts() {
    }

    public static BigDecimal beforeVat(int quantity, BigDecimal unitPrice) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal vat(int quantity, BigDecimal unitPrice, BigDecimal vatRate) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).multiply(vatRate).setScale(2, RoundingMode.HALF_UP);
    }

    /** quantity * unit_price * (1 + vat_rate) CHƯA làm tròn: trần của giảm giá (EX-14). */
    public static BigDecimal grossWithVat(int quantity, BigDecimal unitPrice, BigDecimal vatRate) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).multiply(BigDecimal.ONE.add(vatRate));
    }

    public static BigDecimal payable(int quantity, BigDecimal unitPrice, BigDecimal vatRate, BigDecimal discount) {
        return grossWithVat(quantity, unitPrice, vatRate).subtract(discount).setScale(2, RoundingMode.HALF_UP);
    }
}
