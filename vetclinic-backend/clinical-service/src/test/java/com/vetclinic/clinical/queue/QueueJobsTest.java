package com.vetclinic.clinical.queue;

import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Dựng lại lúc khởi động (khi mất khóa meta) và đối soát định kỳ (bù phiếu thiếu); Redis sập thì chỉ ghi log, không làm hỏng service. */
@ExtendWith(MockitoExtension.class)
class QueueJobsTest {

    @Mock
    private QueueRebuildService rebuild;

    @Test
    void startup_rebuildsOnlyWhenTodaysMetaKeyIsGone() {
        QueueRebuildJob job = new QueueRebuildJob(rebuild);
        when(rebuild.metaPresent()).thenReturn(true, false);
        when(rebuild.rebuild(Duration.ZERO)).thenReturn(new QueueRebuildService.Result(true, 2, 1, 0));

        job.onStartup();   // Redis còn nguyên dữ liệu của hôm nay
        verify(rebuild, never()).rebuild(any());

        job.onStartup();   // Redis mới / vừa bị xóa: dựng lại ngay, không chờ mức tuổi tối thiểu
        verify(rebuild).rebuild(Duration.ZERO);
    }

    @Test
    void startup_aRedisOutageIsOnlyLogged_theServiceStillStarts() {
        QueueRebuildJob job = new QueueRebuildJob(rebuild);
        when(rebuild.metaPresent()).thenThrow(new AppException(ErrorCode.QUEUE_UNAVAILABLE));

        job.onStartup();   // không ném: QueueReconcileJob thử lại sau
    }

    @Test
    void reconcile_rebuildsEverythingWhenMetaIsGone_otherwiseOnlyFillsInTheOldMissingTickets() {
        QueueReconcileJob job = new QueueReconcileJob(rebuild, 60);
        when(rebuild.metaPresent()).thenReturn(false, true);
        when(rebuild.rebuild(any())).thenReturn(new QueueRebuildService.Result(true, 0, 0, 0));

        job.reconcile();
        verify(rebuild).rebuild(Duration.ZERO);                  // Redis bị xóa lúc đang chạy: dựng lại toàn bộ

        job.reconcile();
        verify(rebuild).rebuild(Duration.ofSeconds(60));          // chỉ bù khoản đã quá 60 giây (sự kiện mới hơn còn đang tới)
    }

    @Test
    void reconcile_aRedisOutageIsOnlyLogged() {
        QueueReconcileJob job = new QueueReconcileJob(rebuild, 60);
        when(rebuild.metaPresent()).thenThrow(new AppException(ErrorCode.QUEUE_UNAVAILABLE));

        job.reconcile();   // không ném: lịch @Scheduled không bị hủy
    }
}
