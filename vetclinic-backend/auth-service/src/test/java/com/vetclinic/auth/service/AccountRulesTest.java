package com.vetclinic.auth.service;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.auth.entity.User;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountRulesTest {

    private static User userWith(UserStatus status) {
        return User.builder().username("u").status(status).build();
    }

    @Test
    void anActiveAccountIsUsable() {
        assertThatCode(() -> AccountRules.assertUsable(userWith(UserStatus.ACTIVE))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"LOCKED", "INACTIVE"})
    void lockedAndInactiveAccountsShareOneCode_ACCOUNT_DISABLED_401(UserStatus status) {
        assertThatThrownBy(() -> AccountRules.assertUsable(userWith(status)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_DISABLED);
        assertThat(ErrorCode.ACCOUNT_DISABLED.getCode()).isEqualTo("AUTH_1107");
        assertThat(ErrorCode.ACCOUNT_DISABLED.getHttpStatus().value()).isEqualTo(401);
    }
}
