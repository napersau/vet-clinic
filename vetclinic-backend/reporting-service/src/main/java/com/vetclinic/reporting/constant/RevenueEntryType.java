package com.vetclinic.reporting.constant;

/** Loại dòng doanh thu ({@code revenue_facts.entry_type}; DANH_SACH_KIEU_ENUM_HE_THONG.md v4.0 #18; lưu VARCHAR). */
public enum RevenueEntryType {
    /** Dòng tiền đã thu (số dương). */
    SALE,
    /** Dòng hoàn tiền: sao chép dòng SALE gốc với mọi số tiền ÂM. */
    REFUND
}
