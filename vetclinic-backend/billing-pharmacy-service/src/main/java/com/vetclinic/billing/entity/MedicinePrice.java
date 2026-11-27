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
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Một dòng của lịch sử giá bán thuốc (docs v4.0 mục 41), cùng quy tắc với {@code service_prices} bên clinical. Hiệu lực tại thời điểm t:
 * {@code effectiveFrom <= t AND (effectiveTo IS NULL OR effectiveTo > t)}. Số tiền của một dòng KHÔNG bao giờ bị sửa hay xóa; đổi giá = đóng
 * dòng đang mở + thêm dòng mới (BR-38).
 */
@Entity
@Table(name = "medicine_prices")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicinePrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "medicine_id", nullable = false)
    private Medicine medicine;

    @Column(name = "sale_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "vat_rate", nullable = false, precision = 4, scale = 2)
    private BigDecimal vatRate;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    /** NULL = giá đang áp dụng (dòng mở). */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "change_reason")
    private String changeReason;

    /** Khóa ngoại logic -> auth.users (ADMIN). */
    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
