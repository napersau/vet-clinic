package com.vetclinic.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Dòng thuốc trên hóa đơn (docs v4.0 mục 44), trỏ thẳng {@link Medicine}. {@code prescribedQuantity} là số lượng Bác sĩ kê (lấy từ sự kiện, chỉ để
 * đối chiếu); {@code quantity} là số khách mua: được nhiều hơn / ít hơn / bằng số kê nhưng không vượt tồn kho (BR-32). Giá và VAT chụp từ
 * {@code medicine_prices} đang hiệu lực; được làm mới lúc thu tiền (EX-20).
 */
@Entity
@Table(name = "medicine_invoice_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicineInvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "medicine_invoice_id", nullable = false)
    private MedicineInvoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "medicine_id", nullable = false)
    private Medicine medicine;

    @Column(name = "prescribed_quantity", nullable = false)
    private int prescribedQuantity;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "vat_rate", nullable = false, precision = 4, scale = 2)
    private BigDecimal vatRate;
}
