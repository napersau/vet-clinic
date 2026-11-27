package com.vetclinic.clinical.constant;

/**
 * Thao tác ghi trên lịch sử chỉ định ({@code visit_service_detail_histories.action}, DANH_SACH_KIEU_ENUM_HE_THONG.md v4.0 #11; lưu VARCHAR).
 * Trạng thái con của dịch vụ Lab suy ra từ thao tác MỚI NHẤT: ORDERED -> xếp hàng, START_PROCESSING -> đang lấy mẫu, SAMPLE_COLLECTED -> đang phân tích.
 */
public enum ServiceDetailAction {
    ORDERED,
    START_PROCESSING,
    SAMPLE_COLLECTED,
    COMPLETED,
    CANCELLED
}
