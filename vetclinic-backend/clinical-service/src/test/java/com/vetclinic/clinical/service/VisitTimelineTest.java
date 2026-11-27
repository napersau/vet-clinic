package com.vetclinic.clinical.service;

import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.PetVisitStatusHistory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.vetclinic.clinical.testsupport.TestData.lid;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lược đồ v3.0 bỏ started_at / closed_at / completed_at / cancel_reason khỏi pet_visits: các mốc suy ra từ lịch sử trạng thái.
 */
class VisitTimelineTest {

    private static final Instant T1 = Instant.parse("2026-10-30T01:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-30T01:10:00Z");
    private static final Instant T3 = Instant.parse("2026-10-30T01:20:00Z");
    private static final Instant T4 = Instant.parse("2026-10-30T01:30:00Z");
    private static final Instant T5 = Instant.parse("2026-10-30T01:40:00Z");

    private static PetVisitStatusHistory h(VisitStatus from, VisitStatus to, Instant at, String notes) {
        return PetVisitStatusHistory.builder().visitId(lid(1L)).fromStatus(from).toStatus(to).notes(notes).createdAt(at).build();
    }

    @Test
    void waitingVisit_hasNoTimes() {
        var t = VisitTimeline.compute(VisitStatus.WAITING, List.of(h(null, VisitStatus.WAITING, T1, "Tiếp nhận")));

        assertThat(t.startedAt()).isNull();
        assertThat(t.closedAt()).isNull();
        assertThat(t.completedAt()).isNull();
        assertThat(t.cancelReason()).isNull();
    }

    @Test
    void startedAt_isTheFirstTimeTheVisitEnteredInProgress_evenAfterPhaseTwo() {
        var t = VisitTimeline.compute(VisitStatus.IN_PROGRESS, List.of(
                h(null, VisitStatus.WAITING, T1, null),
                h(VisitStatus.WAITING, VisitStatus.IN_PROGRESS, T2, null),          // bắt đầu khám GĐ 1
                h(VisitStatus.IN_PROGRESS, VisitStatus.WAITING_RESULTS, T3, null),
                h(VisitStatus.WAITING_RESULTS, VisitStatus.RESULTS_READY, T4, null),
                h(VisitStatus.RESULTS_READY, VisitStatus.IN_PROGRESS, T5, null)));   // GĐ 2 không ghi đè mốc bắt đầu

        assertThat(t.startedAt()).isEqualTo(T2);
        assertThat(t.closedAt()).isNull();
    }

    @Test
    void closedAt_isWhenTheDoctorClosed_andCompletedAtWhenPaymentFinished() {
        var t = VisitTimeline.compute(VisitStatus.COMPLETED, List.of(
                h(null, VisitStatus.WAITING, T1, null),
                h(VisitStatus.WAITING, VisitStatus.IN_PROGRESS, T2, null),
                h(VisitStatus.IN_PROGRESS, VisitStatus.WAITING_PAYMENT, T3, null),
                h(VisitStatus.WAITING_PAYMENT, VisitStatus.COMPLETED, T4, null)));

        assertThat(t.closedAt()).isEqualTo(T3);
        assertThat(t.completedAt()).isEqualTo(T4);
        assertThat(t.cancelReason()).isNull();
    }

    @Test
    void reopenedVisit_isNoLongerClosed() {
        var t = VisitTimeline.compute(VisitStatus.IN_PROGRESS, List.of(
                h(VisitStatus.WAITING, VisitStatus.IN_PROGRESS, T2, null),
                h(VisitStatus.IN_PROGRESS, VisitStatus.WAITING_PAYMENT, T3, null),
                h(VisitStatus.WAITING_PAYMENT, VisitStatus.IN_PROGRESS, T4, "Mở lại: nhập sai liều")));

        assertThat(t.closedAt()).isNull();   // đang mở lại để sửa nên chưa "đóng"
        assertThat(t.startedAt()).isEqualTo(T2);
    }

    @Test
    void closedAgainAfterReopen_usesTheLatestClosingTime() {
        var t = VisitTimeline.compute(VisitStatus.WAITING_PAYMENT, List.of(
                h(VisitStatus.WAITING, VisitStatus.IN_PROGRESS, T2, null),
                h(VisitStatus.IN_PROGRESS, VisitStatus.WAITING_PAYMENT, T3, null),
                h(VisitStatus.WAITING_PAYMENT, VisitStatus.IN_PROGRESS, T4, null),
                h(VisitStatus.IN_PROGRESS, VisitStatus.WAITING_PAYMENT, T5, null)));

        assertThat(t.closedAt()).isEqualTo(T5);
    }

    @Test
    void cancelledVisit_exposesTheReasonFromTheHistoryNotes_andClosedAtIsTheCancellationTime() {
        var t = VisitTimeline.compute(VisitStatus.CANCELLED, List.of(
                h(null, VisitStatus.WAITING, T1, null),
                h(VisitStatus.WAITING, VisitStatus.CANCELLED, T2, "Khách bận việc xin về")));

        assertThat(t.cancelReason()).isEqualTo("Khách bận việc xin về");
        assertThat(t.closedAt()).isEqualTo(T2);
        assertThat(t.completedAt()).isNull();
    }

    @Test
    void abandonedVisit_completedWithoutPaymentStep_closedAtIsTheCompletionTime() {
        // Khách bỏ về (T08): RESULTS_READY -> COMPLETED, không qua WAITING_PAYMENT
        var t = VisitTimeline.compute(VisitStatus.COMPLETED, List.of(
                h(VisitStatus.WAITING, VisitStatus.IN_PROGRESS, T2, null),
                h(VisitStatus.RESULTS_READY, VisitStatus.COMPLETED, T4, "Khách bỏ về")));

        assertThat(t.completedAt()).isEqualTo(T4);
        assertThat(t.closedAt()).isEqualTo(T4);
    }
}
