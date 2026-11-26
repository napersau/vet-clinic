package com.vetclinic.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Đọc X-User-Id (UUID) / X-User-Roles (danh sách phân tách dấu phẩy) do Gateway gắn và nạp vào SecurityContext để
 * @PreAuthorize hoạt động: mỗi vai trò thành một quyền {@code ROLE_<mã>}.
 * Cố ý KHÔNG đánh dấu @Component: nếu là bean, Spring Boot sẽ tự đăng ký thêm vào servlet chain và filter chạy 2 lần.
 * Filter chỉ được thêm vào SecurityFilterChain trong CommonSecurityConfig.
 * X-User-Id không phải UUID hợp lệ thì coi như chưa đăng nhập (không nạp SecurityContext).
 */
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String userId = request.getHeader(SecurityConstants.HEADER_USER_ID);
        String userRoles = request.getHeader(SecurityConstants.HEADER_USER_ROLES);

        if (userId != null && !userId.isBlank() && userRoles != null && !userRoles.isBlank() && isUuid(userId.trim())) {
            List<GrantedAuthority> authorities = Arrays.stream(userRoles.split(SecurityConstants.ROLES_SEPARATOR))
                    .map(String::trim)
                    .filter(role -> !role.isEmpty())
                    .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(SecurityConstants.ROLE_PREFIX + role))
                    .toList();
            if (!authorities.isEmpty()) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userId.trim(), null, authorities));
            }
        }

        filterChain.doFilter(request, response);
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
