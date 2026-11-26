package com.vetclinic.auth.dto.response;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.common.security.RoleCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Hồ sơ nhân sự đầy đủ: tài khoản + thông tin cá nhân + hồ sơ nhân sự + (nếu có) hồ sơ chuyên môn thú y.
 * {@code role} là vai trò chính (đứng đầu theo thứ tự {@link RoleCode}), {@code roles} là danh sách đầy đủ.
 */
public record StaffResponse(
        UUID id,
        String username,
        UserStatus status,
        RoleCode role,
        List<RoleCode> roles,
        Instant createdAt,
        Instant lastLoginAt,
        // thông tin cá nhân (user_profiles)
        String fullName,
        String phoneNumber,
        String email,
        String address,
        String gender,
        LocalDate dateOfBirth,
        // hồ sơ nhân sự (staff_profiles)
        String employeeCode,
        String citizenId,
        String positionTitle,
        String department,
        String roomNumber,
        String employmentType,
        LocalDate hireDate,
        LocalDate terminationDate,
        String emergencyContactName,
        String emergencyContactPhone,
        String notes,
        // hồ sơ chuyên môn thú y (veterinarian_profiles); null nếu không có
        VetProfileResponse vetProfile
) {

    public record VetProfileResponse(
            String licenseNumber,
            LocalDate licenseIssuedDate,
            String licenseIssuedBy,
            LocalDate licenseExpiryDate,
            String academicDegree,
            String specialty,
            String graduatedFrom,
            Short careerStartYear,
            String biography
    ) {
    }
}
