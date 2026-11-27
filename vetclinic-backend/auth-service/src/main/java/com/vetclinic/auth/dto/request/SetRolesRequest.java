package com.vetclinic.auth.dto.request;

import com.vetclinic.common.security.RoleCode;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

/** Thay TOÀN BỘ tập vai trò nhân sự của một tài khoản ({@code PUT /api/v1/users/{id}/roles}). */
public record SetRolesRequest(
        @NotEmpty(message = "Phải chọn ít nhất một vai trò")
        Set<RoleCode> roleCodes
) {
}
