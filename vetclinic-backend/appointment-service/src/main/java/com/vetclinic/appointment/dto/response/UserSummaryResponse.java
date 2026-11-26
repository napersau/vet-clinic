package com.vetclinic.appointment.dto.response;

import java.util.UUID;
import java.util.List;
import com.vetclinic.common.security.RoleCode;

/**
 * Phần auth-service trả về mà appointment-service cần. Các trường thừa trong JSON được bỏ qua.
 */
public record UserSummaryResponse(UUID id, String fullName, String phoneNumber, RoleCode role, List<RoleCode> roles, String status) {
}
