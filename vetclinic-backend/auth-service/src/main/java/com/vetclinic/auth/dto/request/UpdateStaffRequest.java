package com.vetclinic.auth.dto.request;

import com.vetclinic.auth.constant.ValidationPatterns;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Admin cập nhật HỒ SƠ NHÂN SỰ (staff_profiles). Trường nào null thì giữ nguyên; roomNumber là chuỗi rỗng thì gỡ phòng.
 * Thông tin cá nhân (họ tên, SĐT...) sửa ở {@code PUT /api/v1/users/{id}/profile}; vai trò ở {@code PUT /api/v1/users/{id}/roles}.
 */
public record UpdateStaffRequest(
        @Pattern(regexp = "^$|" + ValidationPatterns.ROOM_NUMBER, message = ValidationPatterns.ROOM_MESSAGE)
        String roomNumber,
        @Size(min = 1, max = 100, message = "Chức danh từ 1 đến 100 ký tự")
        String positionTitle,
        @Size(max = 100, message = "Bộ phận tối đa 100 ký tự")
        String department,
        @Pattern(regexp = "^(FULL_TIME|PART_TIME)?$", message = "Hình thức làm việc phải là FULL_TIME hoặc PART_TIME")
        String employmentType,
        LocalDate hireDate,
        LocalDate terminationDate,
        @Size(max = 20, message = "Số CCCD tối đa 20 ký tự")
        String citizenId,
        @Size(max = 150, message = "Tên người liên hệ khẩn cấp tối đa 150 ký tự")
        String emergencyContactName,
        @Size(max = 20, message = "SĐT liên hệ khẩn cấp tối đa 20 ký tự")
        String emergencyContactPhone,
        String notes
) {
}
