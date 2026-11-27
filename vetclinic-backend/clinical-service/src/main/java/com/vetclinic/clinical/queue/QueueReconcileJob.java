package com.vetclinic.clinical.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Đối soát định kỳ giữa MySQL (nguồn sự thật) và hàng đợi Redis:
 * <ul>
 *   <li>khóa {@code queue:meta:{ngày}} mất (Redis bị xóa lúc đang chạy) -> dựng lại toàn bộ ngay;</li>
 *   <li>khóa còn: chỉ bổ sung phiếu còn THIẾU của những khoản đã quá {@code min-age} (mặc định 60 giây) - ví dụ sự kiện thu tiền vào DLQ vì Redis sập lâu hơn
 *       thời gian RabbitMQ thử lại (EX-18). Khoản mới hơn mức này còn đang trên đường tới qua sự kiện nên không đụng.</li>
 * </ul>
 * Redis đang sập thì mỗi lần chỉ ghi log. Mọi thao tác idempotent (cùng khóa dedupe với listener), nên không bao giờ sinh phiếu thứ hai.
 */
@Slf4j
@Component
public class QueueReconcileJob {

    private final QueueRebuildService rebuild;
    private final Duration minAge;

    public QueueReconcileJob(QueueRebuildService rebuild, @Value("${vetclinic.queue.reconcile-min-age-seconds:60}") long minAgeSeconds) {
        this.rebuild = rebuild;
        this.minAge = Duration.ofSeconds(minAgeSeconds);
    }

    @Scheduled(fixedDelayString = "${vetclinic.queue.reconcile-delay-ms:60000}", initialDelayString = "${vetclinic.queue.reconcile-initial-delay-ms:60000}")
    public void reconcile() {
        try {
            QueueRebuildService.Result result = rebuild.rebuild(rebuild.metaPresent() ? minAge : Duration.ZERO);
            if (result.total() > 0) {
                log.warn("Đối soát hàng đợi: đã bổ sung {} phiếu Bác sĩ, {} phiếu phòng còn thiếu trên Redis", result.doctorTickets(), result.roomTickets());
            }
        } catch (RuntimeException e) {
            log.warn("Đối soát hàng đợi chưa chạy được (Redis không truy cập được?): {}", e.getMessage());
        }
    }
}
