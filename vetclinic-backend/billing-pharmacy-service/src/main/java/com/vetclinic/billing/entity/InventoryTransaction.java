package com.vetclinic.billing.entity;

import java.util.UUID;
import com.vetclinic.billing.constant.InventoryTransactionType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Nhật ký biến động kho (audit). {@code quantity} CÓ DẤU (+ nhập / - xuất), nên bất biến
 * {@code medicine_stocks.quantity_on_hand = SUM(inventory_transactions.quantity)}. Không lưu tồn sau giao dịch (tính bằng window function).
 * EXPORT_SALE bắt buộc có {@code medicineInvoiceId}.
 */
@Entity
@Table(name = "inventory_transactions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "medicine_id", nullable = false)
    private UUID medicineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private InventoryTransactionType transactionType;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** Giá nhập một đơn vị (chỉ IMPORT). */
    @Column(name = "unit_cost", precision = 12, scale = 2)
    private java.math.BigDecimal unitCost;

    @Column(name = "medicine_invoice_id")
    private UUID medicineInvoiceId;

    @Column(name = "notes", length = 255)
    private String notes;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
