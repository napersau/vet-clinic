package com.vetclinic.reporting.entity;

import com.vetclinic.reporting.constant.RevenueType;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Tổng hợp doanh thu theo ngày / loại / nhóm (schema v4.0 mục 50). CHỈ ĐỌC trong ứng dụng: bảng được cộng dồn bằng
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} ({@code DailyRevenueSummaryRepository.accumulate}) cùng giao dịch với việc ghi {@code revenue_facts}.
 */
@Entity
@Table(name = "daily_revenue_summaries")
@IdClass(DailyRevenueSummaryId.class)
@Getter
@NoArgsConstructor
public class DailyRevenueSummary {

    @Id
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "revenue_type", nullable = false, length = 20)
    private RevenueType revenueType;

    @Id
    @Column(name = "category_code", nullable = false, length = 30)
    private String categoryCode;

    @Column(name = "category_name", nullable = false, length = 100, insertable = false, updatable = false)
    private String categoryName;

    @Column(name = "line_count", nullable = false, insertable = false, updatable = false)
    private int lineCount;

    @Column(name = "amount_before_vat", nullable = false, precision = 16, scale = 2, insertable = false, updatable = false)
    private BigDecimal amountBeforeVat;

    @Column(name = "vat_amount", nullable = false, precision = 16, scale = 2, insertable = false, updatable = false)
    private BigDecimal vatAmount;

    @Column(name = "discount_amount", nullable = false, precision = 16, scale = 2, insertable = false, updatable = false)
    private BigDecimal discountAmount;

    @Column(name = "net_amount", nullable = false, precision = 16, scale = 2, insertable = false, updatable = false)
    private BigDecimal netAmount;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
