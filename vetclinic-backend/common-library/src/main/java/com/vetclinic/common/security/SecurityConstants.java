package com.vetclinic.common.security;

public final class SecurityConstants {

    private SecurityConstants() {
    }

    // Header nội bộ do API Gateway gắn sau khi xác thực JWT (v4.0: id người dùng là UUID, vai trò là DANH SÁCH phân tách dấu phẩy)
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLES = "X-User-Roles";
    public static final String ROLES_SEPARATOR = ",";

    // Tên claim trong JWT (auth-service ký, api-gateway đọc)
    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_FULL_NAME = "fullName";
    public static final String CLAIM_ROOM_NUMBER = "roomNumber";

    public static final String ROLE_PREFIX = "ROLE_";

    // Endpoint nội bộ service-to-service: không được Gateway định tuyến ra ngoài
    public static final String INTERNAL_PATH_PATTERN = "/internal/**";
}
