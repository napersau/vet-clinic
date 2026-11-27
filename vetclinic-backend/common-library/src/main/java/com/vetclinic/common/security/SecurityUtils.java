package com.vetclinic.common.security;

import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** Id (UUID) của người dùng đang đăng nhập; chưa đăng nhập hoặc id sai định dạng thì ném {@code SYS_1002}. */
    public static UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(authentication.getPrincipal().toString());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }

    /** Mọi vai trò của người dùng đang đăng nhập (một người có thể có nhiều vai trò, ví dụ Bác sĩ kiêm Quản trị viên). */
    public static Set<RoleCode> getCurrentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Set<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
        if (authentication == null) {
            return roles;
        }
        authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(SecurityConstants.ROLE_PREFIX))
                .map(a -> a.substring(SecurityConstants.ROLE_PREFIX.length()))
                .flatMap(SecurityUtils::toRoleCode)
                .forEach(roles::add);
        return roles;
    }

    /**
     * Vai trò "chính" để các nơi còn phân nhánh theo một vai trò: vai trò đứng đầu theo thứ tự khai báo của {@link RoleCode}
     * (ADMIN, VETERINARIAN, RECEPTIONIST, ...). Nơi cần kiểm tra quyền chính xác dùng {@link #getCurrentRoles()}.
     */
    public static Optional<RoleCode> getCurrentRole() {
        return getCurrentRoles().stream().findFirst();
    }

    public static boolean hasRole(RoleCode role) {
        return getCurrentRoles().contains(role);
    }

    private static Stream<RoleCode> toRoleCode(String code) {
        try {
            return Stream.of(RoleCode.valueOf(code));
        } catch (IllegalArgumentException e) {
            return Stream.empty();
        }
    }
}
