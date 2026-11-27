package com.vetclinic.auth.constant;

import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;

/** Kênh liên hệ ưu tiên của khách hàng (DANH_SACH_KIEU_ENUM_HE_THONG.md v4.0 #5; lưu pet_owner_profiles.preferred_contact dạng VARCHAR, không dùng ENUM của MySQL). */
public enum ContactChannel {
    PHONE, EMAIL, APP;

    /** Chuỗi rỗng / null -> null (chưa khai báo); giá trị lạ -> 400 SYS_1001. */
    public static ContactChannel parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Kênh liên hệ không hợp lệ: " + value);
        }
    }
}
