package com.vetclinic.clinical.constant;

import java.math.BigDecimal;

/**
 * Đánh giá một chỉ số xét nghiệm so với khoảng tham chiếu của loài. CHỈ dùng ở tầng ứng dụng: lược đồ v3.0 KHÔNG có cột
 * {@code lab_test_results.evaluation} (thuộc tính dẫn xuất), nhãn được tính khi hiển thị từ {@code result_value} so với
 * {@code min_value_snapshot} / {@code max_value_snapshot} (DANH_SACH_KIEU_ENUM_HE_THONG.md mục 11).
 */
public enum LabEvaluation {
    LOW,
    NORMAL,
    HIGH;

    /** LOW khi nhỏ hơn ngưỡng dưới, HIGH khi lớn hơn ngưỡng trên, NORMAL khi nằm trong khoảng (gồm cả hai đầu). */
    public static LabEvaluation of(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0) {
            return LOW;
        }
        if (value.compareTo(max) > 0) {
            return HIGH;
        }
        return NORMAL;
    }
}
