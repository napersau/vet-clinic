package com.vetclinic.auth.mapper;

import com.vetclinic.auth.dto.response.RoleResponse;
import com.vetclinic.auth.dto.response.StaffResponse;
import com.vetclinic.auth.dto.response.UserResponse;
import com.vetclinic.auth.dto.response.UserSummaryResponse;
import com.vetclinic.auth.entity.Role;
import com.vetclinic.auth.entity.StaffProfile;
import com.vetclinic.auth.entity.User;
import com.vetclinic.auth.entity.UserProfile;
import com.vetclinic.auth.entity.VeterinarianProfile;
import com.vetclinic.common.security.RoleCode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Ghép tài khoản + hồ sơ cá nhân + hồ sơ nhân sự thành DTO. Viết tay (không dùng MapStruct) vì dữ liệu nằm trên nhiều thực thể.
 * Phải gọi trong transaction hoặc với User đã nạp kèm vai trò / hồ sơ (open-in-view đã tắt).
 */
@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        UserProfile profile = user.getProfile();
        List<RoleCode> roles = rolesOf(user);
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                profile == null ? null : profile.getFullName(),
                profile == null ? null : profile.getPhoneNumber(),
                profile == null ? null : profile.getEmail(),
                profile == null ? null : profile.getAddress(),
                roomOf(user),
                roles.isEmpty() ? null : roles.get(0),
                roles,
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    public UserSummaryResponse toSummary(User user) {
        UserProfile profile = user.getProfile();
        List<RoleCode> roles = rolesOf(user);
        return new UserSummaryResponse(
                user.getId(),
                profile == null ? null : profile.getFullName(),
                profile == null ? null : profile.getPhoneNumber(),
                profile == null ? null : profile.getEmail(),
                roomOf(user),
                roles.isEmpty() ? null : roles.get(0),
                roles,
                user.getStatus());
    }

    /** Hồ sơ nhân sự đầy đủ; User phải có hồ sơ nhân sự (tài khoản khách hàng không có). */
    public StaffResponse toStaff(User user) {
        UserProfile profile = user.getProfile();
        StaffProfile staff = user.getStaffProfile();
        List<RoleCode> roles = rolesOf(user);
        VeterinarianProfile vet = staff == null ? null : staff.getVetProfile();
        return new StaffResponse(
                user.getId(), user.getUsername(), user.getStatus(), roles.isEmpty() ? null : roles.get(0), roles,
                user.getCreatedAt(), user.getLastLoginAt(),
                profile == null ? null : profile.getFullName(),
                profile == null ? null : profile.getPhoneNumber(),
                profile == null ? null : profile.getEmail(),
                profile == null ? null : profile.getAddress(),
                profile == null || profile.getGender() == null ? null : profile.getGender().name(),
                profile == null ? null : profile.getDateOfBirth(),
                staff == null ? null : staff.getEmployeeCode(),
                staff == null ? null : staff.getCitizenId(),
                staff == null ? null : staff.getPositionTitle(),
                staff == null ? null : staff.getDepartment(),
                staff == null ? null : staff.getRoomNumber(),
                staff == null || staff.getEmploymentType() == null ? null : staff.getEmploymentType().name(),
                staff == null ? null : staff.getHireDate(),
                staff == null ? null : staff.getTerminationDate(),
                staff == null ? null : staff.getEmergencyContactName(),
                staff == null ? null : staff.getEmergencyContactPhone(),
                staff == null ? null : staff.getNotes(),
                vet == null ? null : new StaffResponse.VetProfileResponse(vet.getLicenseNumber(), vet.getLicenseIssuedDate(),
                        vet.getLicenseIssuedBy(), vet.getLicenseExpiryDate(), vet.getAcademicDegree(), vet.getSpecialty(),
                        vet.getGraduatedFrom(), vet.getCareerStartYear(), vet.getBiography()));
    }

    public RoleResponse toRole(Role role) {
        return new RoleResponse(role.getId(), role.getCode(), role.getName(), role.getDescription(), role.isSystem(), role.isActive());
    }

    /** Vai trò của người dùng theo thứ tự khai báo của {@link RoleCode} (vai trò chính đứng đầu). */
    public static List<RoleCode> rolesOf(User user) {
        return user.getRoles().stream().map(Role::getCode).sorted(Comparator.naturalOrder()).toList();
    }

    private static String roomOf(User user) {
        StaffProfile staff = user.getStaffProfile();
        return staff == null ? null : staff.getRoomNumber();
    }
}
