package com.vetclinic.notification.security;

import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityConstants;
import com.vetclinic.notification.config.NotificationProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Kiểm tra Access Token do auth-service ký (HS256) cho kết nối STOMP. Trình duyệt không gắn được header Authorization
 * cho WebSocket nên Gateway cho /ws/** đi qua và token được kiểm tra ở đây, trong frame CONNECT.
 * Claim: userId (UUID dạng chuỗi) và roles (DANH SÁCH mã vai trò thô), giống Gateway.
 */
@Slf4j
@Component
public class JwtTokenVerifier {

    public record AuthenticatedUser(UUID userId, Set<RoleCode> roles) {
    }

    private final SecretKey signingKey;

    public JwtTokenVerifier(NotificationProperties properties) {
        this.signingKey = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public Optional<AuthenticatedUser> verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
            UUID userId = UUID.fromString(String.valueOf(claims.get(SecurityConstants.CLAIM_USER_ID)));
            Set<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
            if (claims.get(SecurityConstants.CLAIM_ROLES) instanceof Collection<?> codes) {
                for (Object code : codes) {
                    try {
                        roles.add(RoleCode.valueOf(String.valueOf(code)));
                    } catch (IllegalArgumentException ignored) {
                        // mã vai trò lạ: bỏ qua, không cấp quyền nào cho nó
                    }
                }
            }
            if (roles.isEmpty()) {
                return Optional.empty();   // token không mang vai trò hợp lệ nào (kể cả token v3 chỉ có claim "role")
            }
            return Optional.of(new AuthenticatedUser(userId, roles));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT của kết nối STOMP không hợp lệ: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
