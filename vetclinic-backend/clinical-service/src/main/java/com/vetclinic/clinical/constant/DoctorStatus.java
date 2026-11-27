package com.vetclinic.clinical.constant;

/**
 * Trạng thái phục vụ của Bác sĩ. KHÔNG lưu trong DB, tính động từ các ca khám (state machine SM-02).
 * v3.0 chỉ còn 2 giá trị: không còn "Khóa ưu tiên" (AVAILABLE_PRIORITY_CALLBACK đã bỏ).
 */
public enum DoctorStatus {
    AVAILABLE,
    BUSY
}
