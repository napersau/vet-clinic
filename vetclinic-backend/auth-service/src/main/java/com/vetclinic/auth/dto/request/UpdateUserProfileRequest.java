package com.vetclinic.auth.dto.request;

import com.vetclinic.auth.constant.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Cập nhật hồ sơ CÁ NHÂN của một người dùng ({@code PUT /api/v1/users/{id}/profile}): Admin cho mọi tài khoản, Lễ tân chỉ cho
 * khách hàng. Trường nào null thì giữ nguyên. Đổi họ tên / SĐT / email phát {@code UserProfileUpdatedEvent} (outbox).
 */
public record UpdateUserProfileRequest(
        @Size(min = 1, max = 150, message = "Họ tên từ 1 đến 150 ký tự")
        String fullName,
        @Pattern(regexp = ValidationPatterns.PHONE, message = ValidationPatterns.PHONE_MESSAGE)
        String phoneNumber,
        @Email(message = "Email không hợp lệ")
        @Size(max = 150, message = "Email tối đa 150 ký tự")
        String email,
        @Size(max = 255, message = "Địa chỉ tối đa 255 ký tự")
        String address,
        @Pattern(regexp = "^(MALE|FEMALE|OTHER)?$", message = "Giới tính phải là MALE, FEMALE hoặc OTHER")
        String gender,
        LocalDate dateOfBirth
) {
}
