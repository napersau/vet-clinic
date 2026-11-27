package com.vetclinic.billing.service;

import com.vetclinic.billing.entity.MedicineInvoiceItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Công thức phải khớp VIEW v_medicine_invoice_amounts; ví dụ chuẩn của Lộ trình: 10 × 15.000 × 1,05 = 157.500đ. */
class MedicineInvoiceAmountsTest {

    private static MedicineInvoiceItem line(int quantity, String price, String vat) {
        return MedicineInvoiceItem.builder().quantity(quantity).unitPrice(new BigDecimal(price)).vatRate(new BigDecimal(vat)).build();
    }

    @Test
    void standardFlow_tenClavamoxAtFivePercentVat() {
        List<MedicineInvoiceItem> items = List.of(line(10, "15000", "0.05"));

        assertThat(MedicineInvoiceAmounts.beforeVat(items)).isEqualByComparingTo("150000.00");
        assertThat(MedicineInvoiceAmounts.vat(items)).isEqualByComparingTo("7500.00");
        assertThat(MedicineInvoiceAmounts.payable(items, BigDecimal.ZERO)).isEqualByComparingTo("157500.00");
    }

    @Test
    void editedToEightPills_keepsSnapshotPrice() {
        assertThat(MedicineInvoiceAmounts.payable(List.of(line(8, "15000", "0.05")), BigDecimal.ZERO))
                .isEqualByComparingTo("126000.00");
    }

    @Test
    void eachLineKeepsItsOwnVatRate_andDiscountIsSubtractedFromTheTotal() {
        List<MedicineInvoiceItem> items = List.of(line(10, "15000", "0.05"), line(1, "180000", "0.10"));

        assertThat(MedicineInvoiceAmounts.beforeVat(items)).isEqualByComparingTo("330000.00");
        assertThat(MedicineInvoiceAmounts.vat(items)).isEqualByComparingTo("25500.00");   // 7.500 + 18.000
        assertThat(MedicineInvoiceAmounts.payable(items, new BigDecimal("5500"))).isEqualByComparingTo("350000.00");
        assertThat(MedicineInvoiceAmounts.gross(items)).isEqualByComparingTo("355500");   // trần của giảm giá
    }

    @Test
    void roundsHalfUpToTwoDecimals() {
        // 3 × 333,33 × 1,05 = 1049,9895 -> 1049,99 (HALF_UP)
        assertThat(MedicineInvoiceAmounts.payable(List.of(line(3, "333.33", "0.05")), BigDecimal.ZERO))
                .isEqualByComparingTo("1049.99");
    }
}
