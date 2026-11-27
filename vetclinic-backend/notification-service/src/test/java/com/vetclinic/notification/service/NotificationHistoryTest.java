package com.vetclinic.notification.service;

import com.vetclinic.common.security.RoleCode;
import com.vetclinic.notification.config.NotificationProperties;
import com.vetclinic.notification.model.NotificationMessage;
import com.vetclinic.notification.model.NotificationType;
import com.vetclinic.notification.model.Severity;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationHistoryTest {

    /** UUID demo cố định, cùng dạng với dữ liệu seed (019a9442-d810-7000-8000-0000000000NN). */
    private static UUID uid(long n) {
        return UUID.fromString(String.format("019a9442-d810-7000-8000-%012d", n));
    }


    private static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";

    private static NotificationMessage message(String title, long second) {
        return new NotificationMessage(UUID.randomUUID(), NotificationType.LOW_STOCK, Severity.INFO, title, "",
                Map.of(), Instant.parse("2026-10-30T02:00:00Z").plusSeconds(second), false);
    }

    private static NotificationHistory history(int capacity) {
        return new NotificationHistory(new NotificationProperties(SECRET, "a@b", capacity, "Asia/Ho_Chi_Minh"));
    }

    @Test
    void recent_mergesPersonalAndRoleMessagesNewestFirst() {
        NotificationHistory history = history(10);
        history.addForUser(uid(7), message("riêng 1", 1));
        history.addForRole(RoleCode.RECEPTIONIST, message("chung 2", 2));
        history.addForUser(uid(7), message("riêng 3", 3));

        assertThat(history.recent(uid(7), Set.of(RoleCode.RECEPTIONIST), 10)).extracting(NotificationMessage::title)
                .containsExactly("riêng 3", "chung 2", "riêng 1");
    }

    @Test
    void recent_doesNotLeakOtherUsersOrOtherRoles() {
        NotificationHistory history = history(10);
        history.addForUser(uid(8), message("của người khác", 1));
        history.addForRole(RoleCode.ADMIN, message("của admin", 2));

        assertThat(history.recent(uid(7), Set.of(RoleCode.RECEPTIONIST), 10)).isEmpty();
    }

    @Test
    void recent_isBoundedPerBucketAndHonoursTheLimit() {
        NotificationHistory history = history(3);
        for (int i = 1; i <= 5; i++) {
            history.addForUser(uid(7), message("m" + i, i));
        }

        assertThat(history.recent(uid(7), Set.of(RoleCode.ADMIN), 100)).extracting(NotificationMessage::title)
                .containsExactly("m5", "m4", "m3");   // cũ nhất bị loại
        assertThat(history.recent(uid(7), Set.of(RoleCode.ADMIN), 2)).hasSize(2);
        assertThat(history.recent(uid(7), Set.of(RoleCode.ADMIN), 0)).isEmpty();
    }

    @Test
    void markRead_isPerUser_idempotent_andOnlyForMessagesTheUserCanSee() {
        NotificationHistory history = history(10);
        NotificationMessage personal = message("riêng", 1);
        NotificationMessage shared = message("chung", 2);
        history.addForUser(uid(7), personal);
        history.addForRole(RoleCode.RECEPTIONIST, shared);

        assertThat(history.recent(uid(7), Set.of(RoleCode.RECEPTIONIST), 10)).extracting(NotificationMessage::read).containsOnly(false);
        assertThat(history.markRead(uid(7), Set.of(RoleCode.RECEPTIONIST), shared.id())).isTrue();
        assertThat(history.markRead(uid(7), Set.of(RoleCode.RECEPTIONIST), shared.id())).isTrue();   // idempotent
        assertThat(history.recent(uid(7), Set.of(RoleCode.RECEPTIONIST), 10)).extracting(NotificationMessage::title, NotificationMessage::read)
                .containsExactly(Tuple.tuple("chung", true), Tuple.tuple("riêng", false));

        // thông báo chung của vai trò có trạng thái đọc riêng từng người
        assertThat(history.recent(uid(8), Set.of(RoleCode.RECEPTIONIST), 10)).extracting(NotificationMessage::read).containsOnly(false);

        // không đánh dấu được thông báo của người khác / vai trò khác / id lạ
        assertThat(history.markRead(uid(8), Set.of(RoleCode.ADMIN), personal.id())).isFalse();
        assertThat(history.markRead(uid(7), Set.of(RoleCode.RECEPTIONIST), UUID.randomUUID())).isFalse();
    }

    @Test
    void processedEvents_runsAnActionOnlyOnceAfterItSucceeded() {
        ProcessedEvents processed = new ProcessedEvents();
        AtomicInteger runs = new AtomicInteger();

        processed.runOnce("k", runs::incrementAndGet);
        processed.runOnce("k", runs::incrementAndGet);
        processed.runOnce("other", runs::incrementAndGet);

        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void processedEvents_aFailedActionIsNotRemembered_soTheRetryRunsAgain() {
        ProcessedEvents processed = new ProcessedEvents();
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> processed.runOnce("k", () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("smtp down");
        })).isInstanceOf(IllegalStateException.class);
        processed.runOnce("k", runs::incrementAndGet);

        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void aUserWithSeveralRolesSeesTheSharedMessagesOfEveryRole_withoutDuplicates() {
        NotificationHistory history = history(10);
        history.addForRole(RoleCode.RECEPTIONIST, message("của lễ tân", 1));
        history.addForRole(RoleCode.ADMIN, message("của admin", 2));
        history.addForUser(uid(7), message("riêng", 3));

        assertThat(history.recent(uid(7), Set.of(RoleCode.RECEPTIONIST, RoleCode.ADMIN), 10)).extracting(NotificationMessage::title)
                .containsExactly("riêng", "của admin", "của lễ tân");
        assertThat(history.recent(uid(7), Set.of(RoleCode.RECEPTIONIST), 10)).extracting(NotificationMessage::title)
                .containsExactly("riêng", "của lễ tân");
    }
}
