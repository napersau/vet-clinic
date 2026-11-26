package com.vetclinic.auth.constant;

import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;

/** Giới tính người dùng (DANH_SACH_KIEU_ENUM_HE_THONG.md v4.0 #2; lưu user_profiles.gender dạng VARCHAR, không dùng ENUM của MySQL). */
public enum Gender {
    MALE, FEMALE, OTHER;

    /** Chuỗi rỗng / null -> null (chưa khai báo); giá trị lạ -> 400 SYS_1001. */
    public static Gender parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Giới tính không hợp lệ: " + value);
        }
    }
}
