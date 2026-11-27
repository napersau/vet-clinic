package com.vetclinic.auth.constant;

import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;

/** Nguồn tạo hồ sơ khách hàng (DANH_SACH_KIEU_ENUM_HE_THONG.md v4.0 #4; lưu pet_owner_profiles.registered_channel dạng VARCHAR, không dùng ENUM của MySQL). */
public enum RegisteredChannel {
    ONLINE, AT_CLINIC;

    /** Chuỗi rỗng / null -> null (chưa khai báo); giá trị lạ -> 400 SYS_1001. */
    public static RegisteredChannel parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Nguồn tạo hồ sơ không hợp lệ: " + value);
        }
    }
}
