package com.vetclinic.auth.service;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.auth.dto.request.CreateStaffRequest;
import com.vetclinic.auth.dto.request.UpdateStaffRequest;
import com.vetclinic.auth.dto.request.VetProfileRequest;
import com.vetclinic.auth.dto.response.StaffResponse;
import com.vetclinic.common.dto.PageResponse;
import com.vetclinic.common.security.RoleCode;

import java.util.UUID;

/** Quản lý NHÂN SỰ (FR-01, FR-02): tài khoản + hồ sơ cá nhân + hồ sơ nhân sự + hồ sơ chuyên môn Bác sĩ. Chỉ Admin. */
public interface StaffService {

    /**
     * Tạo nhân sự trong MỘT giao dịch: {@code users + user_roles + user_profiles + staff_profiles} (+ {@code veterinarian_profiles}).
     * Có vai trò VETERINARIAN mà thiếu hồ sơ chuyên môn thì {@code AUTH_1108}. Không nhận vai trò PET_OWNER.
     */
    StaffResponse create(CreateStaffRequest request);

    PageResponse<StaffResponse> search(RoleCode role, UserStatus status, String keyword, int page, int size);

    StaffResponse getById(UUID id);

    /** Cập nhật hồ sơ nhân sự (phòng, chức danh, bộ phận, CCCD, liên hệ khẩn cấp...). Đổi phòng phát {@code UserProfileUpdatedEvent}. */
    StaffResponse update(UUID id, UpdateStaffRequest request);

    /** Tạo mới hoặc cập nhật hồ sơ chuyên môn thú y (số chứng chỉ phải duy nhất). */
    StaffResponse upsertVetProfile(UUID id, VetProfileRequest request);
}
