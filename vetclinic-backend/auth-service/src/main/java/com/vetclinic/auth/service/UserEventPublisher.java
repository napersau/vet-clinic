package com.vetclinic.auth.service;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.auth.entity.StaffProfile;
import com.vetclinic.auth.entity.User;
import com.vetclinic.auth.mapper.UserMapper;
import com.vetclinic.common.event.UserProfileUpdatedEvent;
import com.vetclinic.common.event.UserStatusChangedEvent;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.security.SecurityUtils;
import com.vetclinic.common.util.UuidV7;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Ghi {@link UserStatusChangedEvent} / {@link UserProfileUpdatedEvent} vào outbox CÙNG giao dịch với thay đổi tài khoản
 * ({@code OutboxWriter} bắt buộc phải gọi trong giao dịch đang chạy). {@code OutboxRelay} gửi RabbitMQ sau khi commit.
 */
@Component
@RequiredArgsConstructor
public class UserEventPublisher {

    /** aggregate_type của các dòng outbox của tài khoản (aggregate_id = id tài khoản). */
    public static final String AGGREGATE = "USER";

    private final OutboxWriter outboxWriter;
    private final Clock clock;

    /** Trạng thái đổi (khóa / mở khóa / ngừng sử dụng). */
    public void statusChanged(User user, UserStatus from, UserStatus to) {
        outboxWriter.write(AGGREGATE, user.getId(), new UserStatusChangedEvent(UuidV7.generate(clock), clock.instant(), actor(),
                user.getId(), user.getProfile() == null ? null : user.getProfile().getFullName(), from.name(), to.name(),
                UserMapper.rolesOf(user).stream().map(Enum::name).toList()));
    }

    /** Hồ sơ cá nhân hoặc phòng làm việc đổi: gửi giá trị MỚI để consumer cập nhật cột snapshot. */
    public void profileUpdated(User user) {
        StaffProfile staff = user.getStaffProfile();
        outboxWriter.write(AGGREGATE, user.getId(), new UserProfileUpdatedEvent(UuidV7.generate(clock), clock.instant(), actor(),
                user.getId(), user.getProfile().getFullName(), user.getProfile().getPhoneNumber(), user.getProfile().getEmail(),
                staff == null ? null : staff.getRoomNumber()));
    }

    /** Người thao tác; không có (gọi ngoài ngữ cảnh người dùng) thì null = hệ thống. */
    private static UUID actor() {
        try {
            return SecurityUtils.getCurrentUserId();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
