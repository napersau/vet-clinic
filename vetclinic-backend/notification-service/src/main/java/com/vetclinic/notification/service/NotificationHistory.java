package com.vetclinic.notification.service;

import com.vetclinic.common.security.RoleCode;
import com.vetclinic.notification.config.NotificationProperties;
import com.vetclinic.notification.model.NotificationMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Lịch sử gần nhất trong BỘ NHỚ (notification-service không có CSDL): để client vừa đăng nhập hoặc vừa kết nối lại lấy
 * được các thông báo đã lỡ qua GET /api/v1/notifications. Mất khi service khởi động lại; chỉ đúng với một instance.
 */
@Component
public class NotificationHistory {

    private final int capacity;
    private final Map<String, Deque<NotificationMessage>> buckets = new ConcurrentHashMap<>();
    /** Đã đọc theo từng người dùng (thông báo chung của vai trò có trạng thái đọc riêng từng người). Giữ tối đa 4 x capacity id mới nhất. */
    private final Map<UUID, Set<UUID>> readByUser = new ConcurrentHashMap<>();

    public NotificationHistory(NotificationProperties properties) {
        this.capacity = properties.historySize();
    }

    public void addForUser(UUID userId, NotificationMessage message) {
        add("user:" + userId, message);
    }

    public void addForRole(RoleCode role, NotificationMessage message) {
        add("role:" + role.name(), message);
    }

    /** Thông báo riêng của người dùng cộng thông báo chung của vai trò, mới nhất trước, kèm trạng thái đã đọc của người dùng đó. */
    public List<NotificationMessage> recent(UUID userId, Set<RoleCode> roles, int limit) {
        Set<UUID> read = readByUser.getOrDefault(userId, Set.of());
        return visibleTo(userId, roles)
                .sorted(Comparator.comparing(NotificationMessage::createdAt).reversed())
                .limit(Math.max(limit, 0))
                .map(m -> m.withRead(read.contains(m.id())))
                .toList();
    }

    /**
     * Đánh dấu đã đọc (idempotent). Chỉ thông báo người dùng nhìn thấy được (của riêng họ hoặc của vai trò họ) mới đánh dấu được:
     * trả false nếu không có (id lạ, của người khác, hoặc đã trôi khỏi lịch sử).
     */
    public boolean markRead(UUID userId, Set<RoleCode> roles, UUID id) {
        boolean visible = visibleTo(userId, roles).anyMatch(m -> m.id().equals(id));
        if (!visible) {
            return false;
        }
        int limit = capacity * 4;
        readByUser.computeIfAbsent(userId, k -> Collections.synchronizedSet(Collections.newSetFromMap(
                new LinkedHashMap<UUID, Boolean>(16, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                        return size() > limit;
                    }
                }))).add(id);
        return true;
    }

    /** Thông báo riêng của người dùng cộng thông báo chung của TỪNG vai trò họ có (một người có thể kiêm nhiều vai trò). */
    private Stream<NotificationMessage> visibleTo(UUID userId, Set<RoleCode> roles) {
        Stream<NotificationMessage> own = snapshot("user:" + userId).stream();
        Stream<NotificationMessage> shared = roles.stream().flatMap(role -> snapshot("role:" + role.name()).stream());
        return Stream.concat(own, shared).distinct();
    }

    private void add(String key, NotificationMessage message) {
        Deque<NotificationMessage> bucket = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (bucket) {
            bucket.addFirst(message);
            while (bucket.size() > capacity) {
                bucket.removeLast();
            }
        }
    }

    private List<NotificationMessage> snapshot(String key) {
        Deque<NotificationMessage> bucket = buckets.get(key);
        if (bucket == null) {
            return List.of();
        }
        synchronized (bucket) {
            return List.copyOf(bucket);
        }
    }
}
