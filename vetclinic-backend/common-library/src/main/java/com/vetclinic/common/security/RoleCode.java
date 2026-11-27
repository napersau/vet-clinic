package com.vetclinic.common.security;

/**
 * Khớp 100% với roles.code (VARCHAR, bảng danh mục) trong pet_clinic_auth_db. Thứ tự khai báo = thứ tự ưu tiên khi cần chọn một vai trò "chính".
 */
public enum RoleCode {
    ADMIN,
    VETERINARIAN,
    RECEPTIONIST,
    LAB_TECHNICIAN,
    IMAGING_TECHNICIAN,
    PET_OWNER
}
