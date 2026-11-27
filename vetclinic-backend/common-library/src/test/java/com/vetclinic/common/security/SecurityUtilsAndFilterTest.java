package com.vetclinic.common.security;

import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Gateway gắn X-User-Id (UUID) + X-User-Roles (danh sách); filter nạp SecurityContext, SecurityUtils đọc lại. */
class SecurityUtilsAndFilterTest {

    private static final UUID USER = UUID.fromString("019a9442-d810-7000-8000-000000000002");

    private final HeaderAuthenticationFilter filter = new HeaderAuthenticationFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void run(String userId, String roles) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (userId != null) {
            request.addHeader(SecurityConstants.HEADER_USER_ID, userId);
        }
        if (roles != null) {
            request.addHeader(SecurityConstants.HEADER_USER_ROLES, roles);
        }
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);   // luôn cho request đi tiếp; quyền do @PreAuthorize quyết định
    }

    @Test
    void headersBecomeTheUuidUserAndOneAuthorityPerRole() throws Exception {
        run(USER.toString(), "VETERINARIAN,ADMIN");

        assertThat(SecurityUtils.getCurrentUserId()).isEqualTo(USER);
        assertThat(SecurityUtils.getCurrentRoles()).containsExactlyInAnyOrder(RoleCode.VETERINARIAN, RoleCode.ADMIN);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString).containsExactlyInAnyOrder("ROLE_VETERINARIAN", "ROLE_ADMIN");
    }

    @Test
    void theMainRoleFollowsTheDeclarationOrderOfRoleCode() throws Exception {
        run(USER.toString(), "RECEPTIONIST, VETERINARIAN");

        assertThat(SecurityUtils.getCurrentRole()).contains(RoleCode.VETERINARIAN);
        assertThat(SecurityUtils.hasRole(RoleCode.RECEPTIONIST)).isTrue();
        assertThat(SecurityUtils.hasRole(RoleCode.ADMIN)).isFalse();
    }

    @Test
    void unknownRoleCodesAreIgnoredByTheRoleSet() throws Exception {
        run(USER.toString(), "PET_OWNER,SOMETHING_NEW");

        assertThat(SecurityUtils.getCurrentRoles()).isEqualTo(Set.of(RoleCode.PET_OWNER));
    }

    @Test
    void aUserIdThatIsNotAUuidMeansNotLoggedIn() throws Exception {
        run("5", "ADMIN");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThatThrownBy(SecurityUtils::getCurrentUserId)
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void missingHeadersMeanNotLoggedIn() throws Exception {
        run(USER.toString(), null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        run(null, "ADMIN");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(SecurityUtils.getCurrentRoles()).isEmpty();
        assertThat(SecurityUtils.getCurrentRole()).isEmpty();
    }
}
