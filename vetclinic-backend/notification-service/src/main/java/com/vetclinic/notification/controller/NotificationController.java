package com.vetclinic.notification.controller;

import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.SecurityUtils;
import com.vetclinic.notification.model.NotificationMessage;
import com.vetclinic.notification.service.NotificationHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private static final int MAX_LIMIT = 100;

    private final NotificationHistory history;

    /**
     * Thông báo gần nhất của người đăng nhập (riêng + chung của vai trò), mới nhất trước. Dùng khi vừa đăng nhập hoặc
     * kết nối lại WebSocket để lấy các thông báo đã lỡ. Lưu trong bộ nhớ nên mất khi service khởi động lại.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<NotificationMessage>>> recent(@RequestParam(defaultValue = "30") int limit) {
        UUID userId = SecurityUtils.getCurrentUserId();
        var roles = SecurityUtils.getCurrentRoles();
        return ResponseEntity.ok(ApiResponse.success(history.recent(userId, roles, Math.min(Math.max(limit, 1), MAX_LIMIT))));
    }

    /**
     * Đánh dấu một thông báo là đã đọc (idempotent, trạng thái đọc riêng từng người dùng). Id không thuộc thông báo người đăng nhập
     * nhìn thấy được thì trả 404. v3.0 không còn API gửi email công khai: email chỉ do sự kiện RabbitMQ kích hoạt.
     */
    @PostMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> markRead(@PathVariable UUID id) {
        UUID userId = SecurityUtils.getCurrentUserId();
        var roles = SecurityUtils.getCurrentRoles();
        if (!history.markRead(userId, roles, id)) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy thông báo");
        }
        return ResponseEntity.ok(ApiResponse.<Void>success("Đã đánh dấu đã đọc", null));
    }
}
