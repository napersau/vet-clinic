package com.vetclinic.auth.dto.response;

import com.vetclinic.common.security.RoleCode;

/** Một dòng của danh mục vai trò ({@code GET /api/v1/roles}); giao diện dùng để dựng ô chọn vai trò. */
public record RoleResponse(
        Short id,
        RoleCode code,
        String name,
        String description,
        boolean system,
        boolean active
) {
}
