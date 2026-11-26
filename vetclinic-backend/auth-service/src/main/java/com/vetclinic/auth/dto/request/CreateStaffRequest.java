package com.vetclinic.auth.dto.request;

import com.vetclinic.auth.constant.ValidationPatterns;
import com.vetclinic.common.security.RoleCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Set;

/**
 * Admin tạo tài khoản NHÂN SỰ trong một giao dịch: {@code users + user_roles + user_profiles + staff_profiles}
 * (+ {@code veterinarian_profiles}, BẮT BUỘC khi có vai trò VETERINARIAN: thiếu thì {@code AUTH_1108}).
 * username bỏ trống thì dùng số điện thoại. roomNumber chỉ dành cho Bác sĩ / KTV (P101–P103, P201–P202...).
 */
public record CreateStaffRequest(
        @Size(max = 100, message = "Tên đăng nhập tối đa 100 ký tự")
        String username,
        @NotBlank(message = "Mật khẩu không được để trống")
        @Pattern(regexp = ValidationPatterns.PASSWORD, message = ValidationPatterns.PASSWORD_MESSAGE)
        String password,
        @NotBlank(message = "Họ tên không được để trống")
        @Size(max = 150, message = "Họ tên tối đa 150 ký tự")
        String fullName,
        @NotBlank(message = "Số điện thoại không được để trống")
        @Pattern(regexp = ValidationPatterns.PHONE, message = ValidationPatterns.PHONE_MESSAGE)
        String phoneNumber,
        @Email(message = "Email không hợp lệ")
        @Size(max = 150, message = "Email tối đa 150 ký tự")
        String email,
        @Size(max = 255, message = "Địa chỉ tối đa 255 ký tự")
        String address,
        @Pattern(regexp = "^(MALE|FEMALE|OTHER)?$", message = "Giới tính phải là MALE, FEMALE hoặc OTHER")
        String gender,
        LocalDate dateOfBirth,
        @NotEmpty(message = "Phải chọn ít nhất một vai trò")
        Set<RoleCode> roleCodes,
        @Pattern(regexp = ValidationPatterns.ROOM_NUMBER, message = ValidationPatterns.ROOM_MESSAGE)
        String roomNumber,
        @Size(max = 100, message = "Chức danh tối đa 100 ký tự")
        String positionTitle,
        @Size(max = 100, message = "Bộ phận tối đa 100 ký tự")
        String department,
        @Pattern(regexp = "^(FULL_TIME|PART_TIME)?$", message = "Hình thức làm việc phải là FULL_TIME hoặc PART_TIME")
        String employmentType,
        LocalDate hireDate,
        @Size(max = 20, message = "Số CCCD tối đa 20 ký tự")
        String citizenId,
        @Size(max = 150, message = "Tên người liên hệ khẩn cấp tối đa 150 ký tự")
        String emergencyContactName,
        @Size(max = 20, message = "SĐT liên hệ khẩn cấp tối đa 20 ký tự")
        String emergencyContactPhone,
        String notes,
        @Valid
        VetProfileRequest vetProfile
) {
}
