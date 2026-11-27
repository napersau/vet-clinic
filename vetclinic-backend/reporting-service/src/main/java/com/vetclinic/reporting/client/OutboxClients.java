package com.vetclinic.reporting.client;

import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.outbox.OutboxReplayItem;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * CHỈ dùng cho job dựng lại báo cáo (Task 4.5.4): đọc outbox của service nguồn qua endpoint nội bộ {@code GET /internal/outbox/events}
 * (Gateway không định tuyến /internal/**). Lúc chạy bình thường reporting KHÔNG gọi service nào, chỉ nhận sự kiện RabbitMQ.
 * Mỗi service nguồn một interface vì Feign gắn tên service vào interface (không dùng interface cha chung: Feign chỉ hỗ trợ kế thừa hạn chế). Mỗi lời gọi trả MỘT trang: các dòng có {@code id > afterId} và {@code created_at >= since}, theo thứ tự phát sinh.
 */
public final class OutboxClients {

    private OutboxClients() {
    }

    @FeignClient(name = "auth-service", contextId = "authOutbox", path = "/internal/outbox")
    public interface Auth {
        @GetMapping("/events")
        ApiResponse<List<OutboxReplayItem>> events(@RequestParam("since") String since, @RequestParam("routingKeys") List<String> routingKeys,
                                                   @RequestParam("afterId") long afterId, @RequestParam("limit") int limit);
    }

    @FeignClient(name = "clinical-service", contextId = "clinicalOutbox", path = "/internal/outbox")
    public interface Clinical {
        @GetMapping("/events")
        ApiResponse<List<OutboxReplayItem>> events(@RequestParam("since") String since, @RequestParam("routingKeys") List<String> routingKeys,
                                                   @RequestParam("afterId") long afterId, @RequestParam("limit") int limit);
    }

    @FeignClient(name = "billing-pharmacy-service", contextId = "billingOutbox", path = "/internal/outbox")
    public interface Billing {
        @GetMapping("/events")
        ApiResponse<List<OutboxReplayItem>> events(@RequestParam("since") String since, @RequestParam("routingKeys") List<String> routingKeys,
                                                   @RequestParam("afterId") long afterId, @RequestParam("limit") int limit);
    }
}
