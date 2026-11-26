package com.vetclinic.auth.dto.response;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.common.security.RoleCode;

import java.util.List;
import java.util.UUID;

/**
 * Thông tin tối thiểu cho các service khác (hiển thị tên bác sĩ, chủ nuôi, số phòng...). Nơi cần biết một người có phải Bác sĩ hay không
 * phải kiểm tra {@code roles} (một người có thể kiêm nhiều vai trò); {@code role} chỉ là vai trò chính để hiển thị.
 */
public record UserSummaryResponse(
        UUID id,
        String fullName,
        String phoneNumber,
        String email,
        String roomNumber,
        RoleCode role,
        List<RoleCode> roles,
        UserStatus status
) {
}
