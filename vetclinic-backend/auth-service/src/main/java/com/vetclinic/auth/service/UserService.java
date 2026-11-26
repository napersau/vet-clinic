package com.vetclinic.auth.service;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.auth.dto.request.ChangePasswordRequest;
import com.vetclinic.auth.dto.request.CreateUserRequest;
import com.vetclinic.auth.dto.request.RegisterRequest;
import com.vetclinic.auth.dto.request.UpdateProfileRequest;
import com.vetclinic.auth.dto.request.UpdateUserProfileRequest;
import com.vetclinic.auth.dto.response.RoleResponse;
import com.vetclinic.auth.dto.response.UserResponse;
import com.vetclinic.auth.dto.response.UserSummaryResponse;
import com.vetclinic.common.dto.PageResponse;
import com.vetclinic.common.security.RoleCode;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface UserService {

    // --- Người dùng hiện tại ---
    UserResponse getMe();

    UserResponse updateMe(UpdateProfileRequest request);

    /** Đổi mật khẩu và thu hồi mọi Refresh Token (đăng xuất khỏi tất cả thiết bị). */
    void changePassword(ChangePasswordRequest request);

    /** Chủ nuôi tự đăng ký (public): luôn là PET_OWNER, ACTIVE, tên đăng nhập là số điện thoại, kênh ONLINE. */
    UserResponse register(RegisterRequest request);

    // --- Khách hàng (ADMIN, RECEPTIONIST). Nhân sự tạo ở StaffService (POST /api/v1/staff). ---
    /** Tạo tài khoản KHÁCH HÀNG (PET_OWNER, kênh AT_CLINIC). Vai trò khác bị từ chối: nhân sự phải kèm hồ sơ nhân sự nên đi qua /staff. */
    UserResponse create(CreateUserRequest request);

    PageResponse<UserResponse> search(RoleCode role, UserStatus status, String keyword, int page, int size);

    UserResponse getById(UUID id);

    // --- Hồ sơ, vai trò, trạng thái ---
    /**
     * Cập nhật hồ sơ cá nhân: Admin cho mọi tài khoản, Lễ tân chỉ cho khách hàng. Đổi họ tên / SĐT / email ghi
     * {@code UserProfileUpdatedEvent} vào outbox cùng giao dịch.
     */
    UserResponse updateProfile(UUID id, UpdateUserProfileRequest request);

    /**
     * Admin thay TOÀN BỘ tập vai trò nhân sự (không tự đổi của mình, không đổi từ/sang PET_OWNER). Có VETERINARIAN thì tài khoản phải
     * có hồ sơ chuyên môn ({@code AUTH_1108}). Thu hồi mọi Refresh Token để phiên sau mang vai trò mới; số phòng bị xóa nếu không còn
     * vai trò nào có phòng cố định.
     */
    UserResponse setRoles(UUID id, Set<RoleCode> roleCodes);

    /**
     * Đặt lại mật khẩu (quên mật khẩu): Admin cho mọi tài khoản trừ chính mình, Lễ tân chỉ cho chủ nuôi. Thu hồi mọi Refresh
     * Token. Người dùng tự đổi mật khẩu của mình bằng change-password.
     */
    void resetPassword(UUID id, String newPassword);

    /** ACTIVE → LOCKED. Thu hồi mọi Refresh Token; ghi {@code UserStatusChangedEvent}. Không tự khóa chính mình. */
    UserResponse lock(UUID id);

    /** LOCKED / INACTIVE → ACTIVE. Ghi {@code UserStatusChangedEvent}. */
    UserResponse unlock(UUID id);

    /** ACTIVE / LOCKED → INACTIVE (xóa mềm, không xóa cứng). Thu hồi mọi Refresh Token; ghi {@code UserStatusChangedEvent}. */
    UserResponse deactivate(UUID id);

    /** Danh mục vai trò (đọc từ bảng {@code roles}). */
    List<RoleResponse> listRoles();

    List<UserSummaryResponse> getActiveDoctors();

    /** Bác sĩ (có vai trò VETERINARIAN) theo trạng thái tài khoản, xếp theo họ tên. */
    List<UserSummaryResponse> getDoctors(UserStatus status);

    // --- Dùng cho các service khác qua /internal ---
    UserSummaryResponse getSummary(UUID id);

    List<UserSummaryResponse> getSummaries(List<UUID> ids);

    long countActiveDoctors();
}
