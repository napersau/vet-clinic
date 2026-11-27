package com.vetclinic.auth.constant;

import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;

/** Hình thức làm việc của nhân sự (DANH_SACH_KIEU_ENUM_HE_THONG.md v4.0 #3; lưu staff_profiles.employment_type dạng VARCHAR, không dùng ENUM của MySQL). */
public enum EmploymentType {
    FULL_TIME, PART_TIME;

    /** Chuỗi rỗng / null -> null (chưa khai báo); giá trị lạ -> 400 SYS_1001. */
    public static EmploymentType parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Hình thức làm việc không hợp lệ: " + value);
        }
    }
}
