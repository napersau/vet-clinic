package com.vetclinic.auth.security;

import com.vetclinic.auth.config.JwtProperties;
import com.vetclinic.auth.entity.StaffProfile;
import com.vetclinic.auth.entity.User;
import com.vetclinic.auth.mapper.UserMapper;
import com.vetclinic.common.security.SecurityConstants;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Ký Access Token JWT (HS256). api-gateway kiểm tra chữ ký bằng cùng khóa và gắn X-User-Id / X-User-Roles từ claims
 * {@code userId} (UUID dạng chuỗi) / {@code roles} (DANH SÁCH mã vai trò, không tiền tố ROLE_).
 */
@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final JwtProperties properties;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /** User phải được nạp kèm vai trò, hồ sơ cá nhân và hồ sơ nhân sự (đọc họ tên, số phòng). */
    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(user.getId()))
                // userId để dạng chuỗi: Gateway đọc claims.get("userId") rồi đưa nguyên vào header
                .claim(SecurityConstants.CLAIM_USER_ID, String.valueOf(user.getId()))
                .claim(SecurityConstants.CLAIM_ROLES, UserMapper.rolesOf(user).stream().map(Enum::name).toList())
                .claim("username", user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())));
        if (user.getProfile() != null && user.getProfile().getFullName() != null) {
            builder.claim(SecurityConstants.CLAIM_FULL_NAME, user.getProfile().getFullName());
        }
        StaffProfile staff = user.getStaffProfile();
        if (staff != null && staff.getRoomNumber() != null) {
            builder.claim(SecurityConstants.CLAIM_ROOM_NUMBER, staff.getRoomNumber());
        }
        // Chỉ định HS256 tường minh: nếu không, khóa 64 byte sẽ bị tự chọn HS512
        return builder.signWith(signingKey, Jwts.SIG.HS256).compact();
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }
}
