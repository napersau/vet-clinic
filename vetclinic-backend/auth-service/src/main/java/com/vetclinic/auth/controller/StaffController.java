package com.vetclinic.auth.controller;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.auth.dto.request.CreateStaffRequest;
import com.vetclinic.auth.dto.request.UpdateStaffRequest;
import com.vetclinic.auth.dto.request.VetProfileRequest;
import com.vetclinic.auth.dto.response.StaffResponse;
import com.vetclinic.auth.dto.response.UserSummaryResponse;
import com.vetclinic.auth.service.StaffService;
import com.vetclinic.auth.service.UserService;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.dto.PageResponse;
import com.vetclinic.common.security.RoleCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Quản lý nhân sự: tài khoản + hồ sơ nhân sự + hồ sơ chuyên môn Bác sĩ (FR-01, FR-02). */
@RestController
@RequestMapping("/api/v1/staff")
@RequiredArgsConstructor
public class StaffController {

    private final StaffService staffService;
    private final UserService userService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<StaffResponse>> create(@Valid @RequestBody CreateStaffRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Tạo nhân sự thành công", staffService.create(request)));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<StaffResponse>>> search(
            @RequestParam(required = false) RoleCode role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(staffService.search(role, status, keyword, page, size)));
    }

    /** Bác sĩ theo trạng thái tài khoản (mặc định ACTIVE) kèm số phòng: màn hình điều phối và chọn bác sĩ. */
    @GetMapping("/doctors")
    @PreAuthorize("hasAnyRole('ADMIN','RECEPTIONIST')")
    public ResponseEntity<ApiResponse<List<UserSummaryResponse>>> doctors(@RequestParam(required = false) UserStatus status) {
        return ResponseEntity.ok(ApiResponse.success(userService.getDoctors(status)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<StaffResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(staffService.getById(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<StaffResponse>> update(@PathVariable UUID id,
                                                             @Valid @RequestBody UpdateStaffRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật hồ sơ nhân sự thành công", staffService.update(id, request)));
    }

    @PutMapping("/{id}/vet-profile")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<StaffResponse>> upsertVetProfile(@PathVariable UUID id,
                                                                       @Valid @RequestBody VetProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Cập nhật hồ sơ chuyên môn thành công", staffService.upsertVetProfile(id, request)));
    }
}
