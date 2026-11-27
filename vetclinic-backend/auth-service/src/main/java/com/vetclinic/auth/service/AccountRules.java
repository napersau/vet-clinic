package com.vetclinic.auth.service;

import com.vetclinic.auth.entity.User;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Quy tắc dùng chung cho tài khoản, vai trò và mật khẩu (dùng ở {@code UserServiceImpl} và {@code StaffServiceImpl}).
 */
public final class AccountRules {

    /** BCrypt chỉ xử lý 72 byte đầu; Spring Security 7 từ chối mật khẩu dài hơn. */
    public static final int BCRYPT_MAX_BYTES = 72;

    /** Chỉ các vai trò làm việc tại phòng cố định mới có số phòng. */
    public static final Set<RoleCode> ROOM_ROLES =
            EnumSet.of(RoleCode.VETERINARIAN, RoleCode.LAB_TECHNICIAN, RoleCode.IMAGING_TECHNICIAN);

    /** Vai trò Admin được gán cho nhân sự (mọi vai trò trừ PET_OWNER, vốn do khách tự đăng ký hoặc Lễ tân tạo). */
    public static final Set<RoleCode> STAFF_ROLES = EnumSet.complementOf(EnumSet.of(RoleCode.PET_OWNER));

    private AccountRules() {
    }

    public static boolean exceedsBcryptLimit(String rawPassword) {
        return rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }

    public static void requireEncodablePassword(String rawPassword) {
        if (exceedsBcryptLimit(rawPassword)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mật khẩu tối đa 72 byte");
        }
    }

    /**
     * Chỉ tài khoản ACTIVE mới được dùng hệ thống.
     */
    public static void assertUsable(User user) {
        switch (user.getStatus()) {
            case ACTIVE -> {
            }
            case LOCKED, INACTIVE -> throw new AppException(ErrorCode.ACCOUNT_DISABLED);
        }
    }

    /** Số phòng viết hoa, hoặc null nếu để trống. Chỉ Bác sĩ / KTV mới được có số phòng. */
    public static String normalizeRoom(String roomNumber, Set<RoleCode> roles) {
        if (!hasText(roomNumber)) {
            return null;
        }
        if (roles.stream().noneMatch(ROOM_ROLES::contains)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Chỉ Bác sĩ / Kỹ thuật viên mới có số phòng làm việc");
        }
        return roomNumber.trim().toUpperCase(Locale.ROOT);
    }

    /** Bộ phận mặc định ghi vào hồ sơ nhân sự khi tạo / đổi vai trò chính. */
    public static String departmentOf(RoleCode role) {
        return switch (role) {
            case VETERINARIAN -> "Khám bệnh";
            case RECEPTIONIST -> "Tiếp đón";
            case LAB_TECHNICIAN -> "Xét nghiệm";
            case IMAGING_TECHNICIAN -> "Chẩn đoán hình ảnh";
            case ADMIN -> "Ban quản lý";
            case PET_OWNER -> null;
        };
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
