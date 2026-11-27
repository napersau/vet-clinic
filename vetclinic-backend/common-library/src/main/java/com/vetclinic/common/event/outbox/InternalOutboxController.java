package com.vetclinic.common.event.outbox;

import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Đọc outbox của chính service này cho việc dựng lại mô hình đọc báo cáo (Task 4.5.4). Endpoint NỘI BỘ: Gateway không định tuyến
 * {@code /internal/**}, service con cho qua không cần header. CHỈ ĐỌC; không đổi trạng thái dòng outbox, không gửi lại lên RabbitMQ
 * (nên các consumer khác như notification không nhận trùng).
 * <p>
 * Phân trang theo khóa: lần đầu {@code afterId = 0}, các lần sau truyền {@code id} của dòng cuối trang trước; trang rỗng = hết.
 * Là controller thường (Spring 6+ chỉ nhận bean có {@code @Controller} làm handler), nên các service quét {@code com.vetclinic} đều nạp nó kể cả
 * service không có bảng {@code outbox_events}: repository lấy qua {@link ObjectProvider}, service không có outbox trả 404 (không hỏng khởi động).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.data.jpa.repository.JpaRepository")   // service không có JPA (notification) không nạp: lớp tham chiếu kiểu Spring Data
@RequestMapping("/internal/outbox")
public class InternalOutboxController {

    static final int MAX_LIMIT = 1000;

    private final ObjectProvider<OutboxEventRepository> repository;

    public InternalOutboxController(ObjectProvider<OutboxEventRepository> repository) {
        this.repository = repository;
    }

    @GetMapping("/events")
    @Transactional(readOnly = true)
    public ApiResponse<List<OutboxReplayItem>> events(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false) List<String> routingKeys,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "500") int limit) {
        OutboxEventRepository events = repository.getIfAvailable();
        if (events == null) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Service này không có outbox");
        }
        if (routingKeys == null || routingKeys.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Phải chỉ rõ routingKeys cần đọc");
        }
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return ApiResponse.success(events.findForReplay(afterId, since, routingKeys, PageRequest.of(0, size)).stream().map(OutboxReplayItem::of).toList());
    }
}
