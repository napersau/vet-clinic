package com.vetclinic.auth.controller;

import com.vetclinic.auth.dto.request.SetRolesRequest;
import com.vetclinic.auth.dto.request.UpdateUserProfileRequest;
import com.vetclinic.auth.dto.response.UserResponse;
import com.vetclinic.auth.service.UserService;
import com.vetclinic.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Hồ sơ cá nhân, vai trò và trạng thái của một tài khoản (Task 2.4). Chuyển trạng thái dùng {@code POST /{id}/<action>};
 * không xóa cứng: {@code deactivate} là xóa mềm (INACTIVE).
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class AccountController {

    private final UserService userService;

    /** Admin sửa mọi tài khoản; Lễ tân chỉ sửa khách hàng (kiểm tra trong service). */
    @PutMapping("/{id}/profile")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTIONIST')")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(@PathVariable UUID id,
                                                                   @Valid @RequestBody UpdateUserProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật hồ sơ thành công", userService.updateProfile(id, request)));
    }

    /** Thay toàn bộ tập vai trò nhân sự (FR-01); thu hồi phiên đăng nhập của tài khoản đó. */
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> setRoles(@PathVariable UUID id,
                                                              @Valid @RequestBody SetRolesRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật vai trò thành công", userService.setRoles(id, request.roleCodes())));
    }

    @PostMapping("/{id}/lock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> lock(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Đã khóa tài khoản", userService.lock(id)));
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> unlock(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Đã kích hoạt tài khoản", userService.unlock(id)));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>> deactivate(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success("Đã ngừng hoạt động tài khoản", userService.deactivate(id)));
    }
}
