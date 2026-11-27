package com.vetclinic.reporting.entity;

import java.io.Serializable;
import java.time.LocalDate;

/** Khóa chính (business_date, revenue_type, category_code) của {@link DailyRevenueSummary}. */
public record DailyRevenueSummaryId(LocalDate businessDate, com.vetclinic.reporting.constant.RevenueType revenueType, String categoryCode) implements Serializable {
}
