package com.vetclinic.reporting.entity;

import com.vetclinic.reporting.constant.RevenueEntryType;
import com.vetclinic.reporting.constant.RevenueType;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Một dòng tiền đã thu (SALE) hoặc đã hoàn (REFUND, số âm) - schema v4.0 mục 49. {@code revenue_type}: SERVICE | MEDICINE. Ràng buộc
 * UNIQUE(source_invoice_id, item_id, entry_type) là lớp chặn trùng thứ hai sau {@code processed_events}. Doanh thu thật = SUM(net_amount).
 */
@Entity
@Table(name = "revenue_facts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "revenue_type", nullable = false, length = 20)
    private RevenueType revenueType;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private RevenueEntryType entryType;

    @Column(name = "source_invoice_id", nullable = false)
    private UUID sourceInvoiceId;

    @Column(name = "invoice_code", nullable = false, length = 50)
    private String invoiceCode;

    /** service_id hoặc medicine_id */
    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "item_code", nullable = false, length = 50)
    private String itemCode;

    @Column(name = "item_name", nullable = false, length = 150)
    private String itemName;

    @Column(name = "category_code", nullable = false, length = 30)
    private String categoryCode;

    @Column(name = "category_name", nullable = false, length = 100)
    private String categoryName;

    @Column(name = "visit_id", nullable = false)
    private UUID visitId;

    @Column(name = "doctor_id")
    private UUID doctorId;

    @Column(name = "doctor_name", length = 150)
    private String doctorName;

    @Column(name = "cashier_id")
    private UUID cashierId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "amount_before_vat", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountBeforeVat;

    @Column(name = "vat_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal vatAmount;

    @Column(name = "discount_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "net_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal netAmount;
}
