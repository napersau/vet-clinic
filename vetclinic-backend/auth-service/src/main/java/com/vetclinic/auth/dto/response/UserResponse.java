package com.vetclinic.auth.dto.response;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.common.security.RoleCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Hồ sơ tài khoản trả cho giao diện. {@code roles} là danh sách vai trò đầy đủ; {@code role} là vai trò "chính" (đứng đầu theo thứ tự của
 * {@link RoleCode}) để các màn hình còn hiển thị một nhãn duy nhất. {@code roomNumber} lấy từ hồ sơ nhân sự (null với khách hàng).
 */
public record UserResponse(
        UUID id,
        String username,
        String fullName,
        String phoneNumber,
        String email,
        String address,
        String roomNumber,
        RoleCode role,
        List<RoleCode> roles,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
