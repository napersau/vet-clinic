package com.vetclinic.auth.security;

import com.vetclinic.auth.config.JwtProperties;
import com.vetclinic.auth.entity.Role;
import com.vetclinic.auth.entity.StaffProfile;
import com.vetclinic.auth.entity.UserProfile;
import com.vetclinic.auth.entity.User;
import com.vetclinic.common.security.RoleCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token do auth-service ký phải đọc được đúng như api-gateway (JwtValidator) sẽ đọc.
 */
class JwtTokenProviderTest {

    // 64 ký tự (giống `openssl rand -hex 32`): nếu không chỉ định HS256 tường minh, jjwt sẽ tự chọn HS512
    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final JwtProperties properties = new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(7));
    private final JwtTokenProvider provider = new JwtTokenProvider(properties);
    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    @Test
    void accessToken_isSignedWithHs256AndCarriesGatewayClaims() {
        String token = provider.generateAccessToken(vet());

        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);

        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS256");
        Claims claims = jws.getPayload();
        assertThat(claims.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(claims.get("userId", String.class)).isEqualTo(USER_ID.toString()); // UUID dạng chuỗi
        assertThat(claims.get("roles", List.class)).containsExactly("VETERINARIAN");   // DANH SÁCH vai trò
        assertThat(claims).doesNotContainKey("role");
        assertThat(claims.get("username", String.class)).isEqualTo("bs.nguyen");
        assertThat(claims.get("fullName", String.class)).isEqualTo("Nguyễn Văn Tiến");
        assertThat(claims.get("roomNumber", String.class)).isEqualTo("P01");
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isBetween(1_799_000L, 1_801_000L);
    }

    @Test
    void accessToken_withoutRoom_omitsRoomClaim() {
        User user = vet();
        user.getStaffProfile().setRoomNumber(null);

        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(provider.generateAccessToken(user)).getPayload();

        assertThat(claims).doesNotContainKey("roomNumber");
    }

    @Test
    void accessToken_carriesEveryRoleOfTheUser_inDeclarationOrder() {
        User user = vet();
        user.getRoles().add(Role.builder().id((short) 1).code(RoleCode.ADMIN).name("Quản trị viên").build());

        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(provider.generateAccessToken(user)).getPayload();

        assertThat(claims.get("roles", List.class)).containsExactly("ADMIN", "VETERINARIAN");
    }

    @Test
    void accessToken_ofAPetOwner_hasNoRoomClaim() {
        User owner = User.builder().id(USER_ID).username("0912000008").build();
        owner.getRoles().add(Role.builder().id((short) 6).code(RoleCode.PET_OWNER).name("Chủ thú cưng").build());
        owner.setProfile(UserProfile.builder().user(owner).fullName("Hoàng Thị Lan").phoneNumber("0912000008").build());

        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(provider.generateAccessToken(owner)).getPayload();

        assertThat(claims.get("roles", List.class)).containsExactly("PET_OWNER");
        assertThat(claims).doesNotContainKey("roomNumber");
    }

    @Test
    void accessToken_signedWithDifferentKey_isRejected() {
        String token = provider.generateAccessToken(vet());
        SecretKey otherKey = Keys.hmacShaKeyFor("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> Jwts.parser().verifyWith(otherKey).build().parseSignedClaims(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void properties_rejectSecretShorterThan32Bytes() {
        assertThatThrownBy(() -> new JwtProperties("short", Duration.ofMinutes(30), Duration.ofDays(7)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static final UUID USER_ID = UUID.fromString("019a9442-d810-7000-8000-000000000002");

    private static User vet() {
        User user = User.builder().id(USER_ID).username("bs.nguyen").build();
        user.getRoles().add(Role.builder().id((short) 2).code(RoleCode.VETERINARIAN).name("Bác sĩ").build());
        user.setProfile(UserProfile.builder().user(user).fullName("Nguyễn Văn Tiến").phoneNumber("0902000002").build());
        user.setStaffProfile(StaffProfile.builder().user(user).employeeCode("NV0002").positionTitle("Bác sĩ thú y")
                .roomNumber("P01").hireDate(LocalDate.of(2026, 1, 2)).build());
        return user;
    }
}
