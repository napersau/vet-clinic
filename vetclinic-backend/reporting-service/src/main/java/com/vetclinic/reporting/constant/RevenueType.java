package com.vetclinic.reporting.constant;

/** Loại doanh thu ({@code revenue_facts.revenue_type}, {@code daily_revenue_summaries.revenue_type}; DANH_SACH_KIEU_ENUM_HE_THONG.md v4.0 #17; lưu VARCHAR). */
public enum RevenueType {
    /** Hóa đơn dịch vụ (clinical-service). */
    SERVICE,
    /** Hóa đơn thuốc (billing-pharmacy-service). */
    MEDICINE;

    /** Chuỗi rỗng / null -> null (không lọc); giá trị lạ -> {@link IllegalArgumentException}. */
    public static RevenueType parse(String value) {
        return value == null || value.isBlank() ? null : valueOf(value.trim().toUpperCase());
    }
}
