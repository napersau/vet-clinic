package com.vetclinic.clinical.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Khi clinical-service khởi động: nếu khóa {@code queue:meta:{ngày}} của hôm nay không còn (Redis mới / vừa bị xóa) thì dựng lại hàng đợi từ MySQL ngay
 * (Quy chuẩn v4.0 mục 9.3, mục 6). Redis chưa sẵn sàng lúc khởi động thì chỉ ghi log: service vẫn chạy, {@link QueueReconcileJob} thử lại định kỳ. Admin có
 * thể dựng lại bằng tay qua {@code POST /api/v1/clinical/queue-tickets/rebuild}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueRebuildJob {

    private final QueueRebuildService rebuild;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            if (!rebuild.metaPresent()) {
                QueueRebuildService.Result result = rebuild.rebuild(Duration.ZERO);
                log.info("Khởi động: hàng đợi hôm nay chưa được dựng, đã dựng lại từ MySQL ({} phiếu Bác sĩ, {} phiếu phòng)",
                        result.doctorTickets(), result.roomTickets());
            }
        } catch (RuntimeException e) {
            log.warn("Chưa dựng lại được hàng đợi lúc khởi động (Redis chưa sẵn sàng?): {}. QueueReconcileJob sẽ thử lại.", e.getMessage());
        }
    }
}
