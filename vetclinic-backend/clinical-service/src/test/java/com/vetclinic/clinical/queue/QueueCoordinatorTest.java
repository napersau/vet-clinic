package com.vetclinic.clinical.queue;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.QueueTicketHistory;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.QueueTicketHistoryRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.common.event.QueueTicketIssuedEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.OTHER_DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.detail;
import static com.vetclinic.clinical.testsupport.TestData.lid;
import static com.vetclinic.clinical.testsupport.TestData.service;
import static com.vetclinic.clinical.testsupport.TestData.ticket;
import static com.vetclinic.clinical.testsupport.TestData.tid;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vòng đời phiếu trên Redis gắn với giao dịch MySQL. Các test chạy ngoài giao dịch nên "sau commit" thực thi ngay: kiểm tra được cả lịch sử (ghi MySQL)
 * lẫn lệnh Lua (gửi tới Redis).
 */
@ExtendWith(MockitoExtension.class)
class QueueCoordinatorTest {

    private static final UUID VISIT = lid(100L);

    @Mock
    private QueueRedisRepository redis;
    @Mock
    private QueueTicketHistoryRepository histories;
    @Mock
    private VisitServiceDetailRepository detailRepository;
    @Mock
    private ApplicationEventPublisher publisher;

    private QueueCoordinator queue;

    @BeforeEach
    void setUp() {
        queue = new QueueCoordinator(redis, histories, detailRepository, publisher, CLOCK);
    }

    private void openTickets(TicketView... tickets) {
        when(redis.ticketsOf(VISIT)).thenReturn(List.of(tickets));
    }

    // ================================================================== bắt đầu phiếu DOCTOR (T02 / T07)

    @Test
    void startDoctorTicket_startsTheWaitingDoctorTicket_afterCommit() {
        TicketView waiting = ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 0, TicketStatus.WAITING);
        openTickets(waiting, ticket(61L, QueueType.LABORATORY, 100L, null, 1, 0, TicketStatus.WAITING));
        when(redis.start(tid(60L))).thenReturn(QueueOutcome.OK);

        assertThat(queue.startDoctorTicket(VISIT, DOCTOR)).isEqualTo(waiting);

        verify(redis).start(tid(60L));
        verify(redis, never()).start(tid(61L));   // phiếu phòng không phải phiếu DOCTOR
    }

    @Test
    void startDoctorTicket_withoutAWaitingDoctorTicket_isRefused_CLIN_1314() {
        openTickets();
        assertError(ErrorCode.NO_QUEUE_TICKET, () -> queue.startDoctorTicket(VISIT, DOCTOR));   // khách chưa nộp phí khám

        openTickets(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 0, TicketStatus.IN_SERVICE));   // đã bắt đầu rồi
        assertError(ErrorCode.NO_QUEUE_TICKET, () -> queue.startDoctorTicket(VISIT, DOCTOR));
        verify(redis, never()).start(any());
    }

    @Test
    void startDoctorTicket_aTicketStillInTheUnassignedListIsMovedToTheDoctorAndStartedAgain() {
        openTickets(ticket(60L, QueueType.DOCTOR, 100L, null, 1, 0, TicketStatus.WAITING));
        when(redis.start(tid(60L))).thenReturn(QueueOutcome.UNASSIGNED, QueueOutcome.OK);   // phân công vừa commit, move_ticket.lua chưa chạy

        queue.startDoctorTicket(VISIT, DOCTOR);

        verify(redis).move(tid(60L), DOCTOR);
        verify(redis, org.mockito.Mockito.times(2)).start(tid(60L));
    }

    @Test
    void startDoctorTicket_aRedisFailureAfterCommitIsLoggedNotThrown() {
        openTickets(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 0, TicketStatus.WAITING));
        when(redis.start(any())).thenThrow(new AppException(ErrorCode.QUEUE_UNAVAILABLE));

        queue.startDoctorTicket(VISIT, DOCTOR);   // MySQL đã commit: không được ném ngược lên người gọi
    }

    // ================================================================== kết thúc / hủy: lịch sử trong giao dịch + xóa khỏi Redis sau commit

    @Test
    void finishDoctorTicket_writesADoneHistory_andRemovesTheTicketFromRedis() {
        TicketView serving = new TicketView(tid(60L), QueueType.DOCTOR, java.time.LocalDate.of(2026, 10, 30), 7, VISIT, DOCTOR, 1, TicketStatus.IN_SERVICE,
                Instant.parse("2026-10-30T02:00:00Z"), OTHER_DOCTOR, 2, Instant.parse("2026-10-30T02:05:00Z"), Instant.parse("2026-10-30T02:10:00Z"),
                List.of(), "Lu", "Lan", "P101");
        openTickets(serving, ticket(61L, QueueType.DOCTOR, 100L, DOCTOR, 8, 0, TicketStatus.WAITING));   // phiếu chờ khác không bị đụng

        queue.finishDoctorTicket(VISIT);

        ArgumentCaptor<QueueTicketHistory> saved = ArgumentCaptor.forClass(QueueTicketHistory.class);
        verify(histories).save(saved.capture());
        QueueTicketHistory h = saved.getValue();
        assertThat(h.getTicketId()).isEqualTo(tid(60L));
        assertThat(h.getFinalStatus()).isEqualTo(TicketStatus.DONE);
        assertThat(h.getQueueType()).isEqualTo(QueueType.DOCTOR);
        assertThat(h.getTicketNo()).isEqualTo(7);
        assertThat(h.getPriority()).isEqualTo(1);
        assertThat(h.getDoctorId()).isEqualTo(DOCTOR);
        assertThat(h.getIssuedBy()).isEqualTo(OTHER_DOCTOR);
        assertThat(h.getCalledCount()).isEqualTo(2);
        assertThat(h.getIssuedAt()).isEqualTo(Instant.parse("2026-10-30T02:00:00Z"));
        assertThat(h.getStartedAt()).isEqualTo(Instant.parse("2026-10-30T02:10:00Z"));
        assertThat(h.getEndedAt()).isEqualTo(CLOCK.instant());
        assertThat(h.getCancelReason()).isNull();
        verify(redis).finish(tid(60L));
        verify(redis, never()).finish(tid(61L));
    }

    @Test
    void finishDoctorTicket_withoutAServingTicket_doesNothing_reopenedFiles() {
        openTickets(ticket(61L, QueueType.DOCTOR, 100L, DOCTOR, 8, 0, TicketStatus.WAITING));

        queue.finishDoctorTicket(VISIT);

        verify(histories, never()).save(any());
        verify(redis, never()).finish(any());
    }

    @Test
    void cancelActiveTickets_cancelsEveryOpenTicketOfTheVisit_withTheReason() {
        openTickets(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 0, TicketStatus.WAITING),
                ticket(61L, QueueType.LABORATORY, 100L, null, 2, 0, TicketStatus.IN_SERVICE, List.of(lid(1L))));

        queue.cancelActiveTickets(VISIT, "Khách bỏ về");

        ArgumentCaptor<QueueTicketHistory> saved = ArgumentCaptor.forClass(QueueTicketHistory.class);
        verify(histories, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(QueueTicketHistory::getFinalStatus).containsOnly(TicketStatus.CANCELLED);
        assertThat(saved.getAllValues()).extracting(QueueTicketHistory::getCancelReason).containsOnly("Khách bỏ về");
        assertThat(saved.getAllValues().get(1).getDetailIds()).containsExactly(lid(1L));   // dịch vụ của phiếu phòng
        verify(redis).finish(tid(60L));
        verify(redis).finish(tid(61L));
    }

    @Test
    void recordHistory_isIdempotent_aTicketAlreadyInTheHistoryIsNotWrittenTwice() {
        when(histories.existsByTicketId(tid(60L))).thenReturn(true);
        openTickets(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 0, TicketStatus.WAITING));

        queue.cancelActiveTickets(VISIT, "thử lại");

        verify(histories, never()).save(any());
        verify(redis).finish(tid(60L));   // vẫn xóa khỏi Redis (Lua chạy lại an toàn)
    }

    @Test
    void recordHistory_aTicketNumberAlreadyInTheHistory_isSkippedSoTheBusinessActionIsNotBroken() {
        when(histories.existsByQueueTypeAndServiceDateAndTicketNo(QueueType.DOCTOR, java.time.LocalDate.of(2026, 10, 30), 1)).thenReturn(true);   // Redis cấp lại số từ 1
        openTickets(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 0, TicketStatus.WAITING));

        queue.cancelActiveTickets(VISIT, "chốt ca");   // không ném DataIntegrityViolation

        verify(histories, never()).save(any());
        verify(redis).finish(tid(60L));   // phiếu vẫn được xóa khỏi Redis
    }

    @Test
    void cancelReason_isTruncatedToTheColumnSize() {
        openTickets(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 0, TicketStatus.WAITING));

        queue.cancelActiveTickets(VISIT, "x".repeat(400));

        ArgumentCaptor<QueueTicketHistory> saved = ArgumentCaptor.forClass(QueueTicketHistory.class);
        verify(histories).save(saved.capture());
        assertThat(saved.getValue().getCancelReason()).hasSize(255);
    }

    // ================================================================== đổi / gỡ Bác sĩ

    @Test
    void reassignDoctorTicket_movesOnlyTheWaitingDoctorTickets_keepingTheScore() {
        openTickets(ticket(60L, QueueType.DOCTOR, 100L, null, 1, 0, TicketStatus.WAITING),
                ticket(61L, QueueType.DOCTOR, 100L, DOCTOR, 2, 0, TicketStatus.IN_SERVICE),
                ticket(62L, QueueType.LABORATORY, 100L, null, 3, 0, TicketStatus.WAITING));

        queue.reassignDoctorTicket(VISIT, OTHER_DOCTOR);

        verify(redis).move(tid(60L), OTHER_DOCTOR);
        verify(redis, never()).move(eq(tid(61L)), any());
        verify(redis, never()).move(eq(tid(62L)), any());
    }

    // ================================================================== phiếu phòng XN / CĐHA

    @Test
    void refreshRoomTicketOf_aWaitingTicketWithNoPendingServiceLeft_isCancelled() {
        UUID d1 = lid(1L);
        openTickets(ticket(70L, QueueType.LABORATORY, 100L, null, 1, 0, TicketStatus.WAITING, List.of(d1)));
        when(detailRepository.findAllById(List.of(d1))).thenReturn(List.of(
                detail(1L, 100L, service(5L, "LAB_CBC", "LABORATORY", "250000.00"), ServiceDetailStatus.CANCELLED)));

        queue.refreshRoomTicketOf(VISIT, d1);

        ArgumentCaptor<QueueTicketHistory> saved = ArgumentCaptor.forClass(QueueTicketHistory.class);
        verify(histories).save(saved.capture());
        assertThat(saved.getValue().getFinalStatus()).isEqualTo(TicketStatus.CANCELLED);
        verify(redis).finish(tid(70L));
    }

    @Test
    void refreshRoomTicketOf_aServingTicketWhoseServicesAreAllFinished_isDone() {
        UUID d1 = lid(1L);
        UUID d2 = lid(2L);
        openTickets(ticket(71L, QueueType.IMAGING, 100L, null, 1, 0, TicketStatus.IN_SERVICE, List.of(d1, d2)));
        when(detailRepository.findAllById(List.of(d1, d2))).thenReturn(List.of(
                detail(1L, 100L, service(6L, "IMG_XRAY", "IMAGING", "180000.00"), ServiceDetailStatus.COMPLETED),
                detail(2L, 100L, service(7L, "IMG_US", "IMAGING", "150000.00"), ServiceDetailStatus.CANCELLED)));

        queue.refreshRoomTicketOf(VISIT, d1);

        ArgumentCaptor<QueueTicketHistory> saved = ArgumentCaptor.forClass(QueueTicketHistory.class);
        verify(histories).save(saved.capture());
        assertThat(saved.getValue().getFinalStatus()).isEqualTo(TicketStatus.DONE);
        assertThat(saved.getValue().getDetailIds()).containsExactly(d1, d2);
        verify(redis).finish(tid(71L));
    }

    @Test
    void refreshRoomTicketOf_leavesTheTicketAloneWhileServicesAreStillOpen_orWhenThereIsNoTicket() {
        UUID d1 = lid(1L);
        UUID d2 = lid(2L);
        openTickets(ticket(71L, QueueType.IMAGING, 100L, null, 1, 0, TicketStatus.IN_SERVICE, List.of(d1, d2)));
        when(detailRepository.findAllById(List.of(d1, d2))).thenReturn(List.of(
                detail(1L, 100L, service(6L, "IMG_XRAY", "IMAGING", "180000.00"), ServiceDetailStatus.COMPLETED),
                detail(2L, 100L, service(7L, "IMG_US", "IMAGING", "150000.00"), ServiceDetailStatus.IN_PROGRESS)));   // còn đang làm

        queue.refreshRoomTicketOf(VISIT, d1);
        queue.refreshRoomTicketOf(VISIT, lid(99L));   // dịch vụ không thuộc phiếu nào (chưa nộp tiền)

        verify(histories, never()).save(any());
        verify(redis, never()).finish(any());
    }

    @Test
    void roomTicketOf_findsTheOpenRoomTicketHoldingTheService_neverADoctorTicket() {
        TicketView room = ticket(70L, QueueType.LABORATORY, 100L, null, 1, 0, TicketStatus.WAITING, List.of(lid(1L)));
        openTickets(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 0, TicketStatus.WAITING, List.of(lid(1L))), room);

        assertThat(queue.roomTicketOf(VISIT, lid(1L))).contains(room);
        assertThat(queue.roomTicketOf(VISIT, lid(2L))).isEmpty();
    }

    // ================================================================== phát phiếu

    private PetVisit petVisit() {
        return visit(100L, com.vetclinic.clinical.constant.VisitStatus.WAITING, DOCTOR);
    }

    @Test
    void issue_publishesQueueTicketIssuedEvent_forANewTicketOnly() {
        UUID ticketId = tid(60L);
        when(redis.issue(any(IssueTicketRequest.class))).thenReturn(new IssuedTicket(ticketId, 5, false), new IssuedTicket(ticketId, 5, true));
        Instant paidAt = Instant.parse("2026-10-30T02:30:00Z");

        IssuedTicket first = queue.issue(petVisit(), QueueType.DOCTOR, DOCTOR, 0, paidAt, OTHER_DOCTOR, List.of(lid(1L)), "evt:DOCTOR", null);
        queue.issue(petVisit(), QueueType.DOCTOR, DOCTOR, 0, paidAt, OTHER_DOCTOR, List.of(lid(1L)), "evt:DOCTOR", null);   // sự kiện giao lại

        assertThat(first.ticketNo()).isEqualTo(5);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());   // đúng MỘT lần: bản giao lại không thông báo lần hai
        QueueTicketIssuedEvent issued = (QueueTicketIssuedEvent) event.getValue();
        assertThat(issued.ticketId()).isEqualTo(ticketId);
        assertThat(issued.ticketNo()).isEqualTo(5);
        assertThat(issued.queueType()).isEqualTo("DOCTOR");
        assertThat(issued.doctorId()).isEqualTo(DOCTOR);
        assertThat(issued.actorUserId()).isEqualTo(OTHER_DOCTOR);
        assertThat(issued.visitId()).isEqualTo(VISIT);
        ArgumentCaptor<IssueTicketRequest> request = ArgumentCaptor.forClass(IssueTicketRequest.class);
        verify(redis, org.mockito.Mockito.times(2)).issue(request.capture());
        assertThat(request.getAllValues().get(0).issuedAt()).isEqualTo(paidAt);   // score tính từ giờ thu tiền, không phải giờ phát
        assertThat(request.getAllValues().get(0).petName()).isEqualTo("Lu");
        assertThat(request.getAllValues().get(0).ownerName()).isEqualTo("Hoàng Thị Lan");
        assertThat(request.getAllValues().get(0).serviceDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 30));
    }

    @Test
    void issue_forARoomQueueIgnoresTheDoctor() {
        when(redis.issue(any(IssueTicketRequest.class))).thenReturn(new IssuedTicket(tid(70L), 1, false));

        queue.issue(petVisit(), QueueType.LABORATORY, DOCTOR, 0, CLOCK.instant(), null, List.of(lid(1L)), "evt:LABORATORY", null);

        ArgumentCaptor<IssueTicketRequest> request = ArgumentCaptor.forClass(IssueTicketRequest.class);
        verify(redis).issue(request.capture());
        assertThat(request.getValue().doctorId()).isNull();   // phiếu phòng XN / CĐHA không thuộc Bác sĩ nào
    }

    @Test
    void issueAfterCommit_capturesNamesNow_andRedisFailureIsLoggedNotThrown() {
        when(redis.issue(any(IssueTicketRequest.class))).thenThrow(new AppException(ErrorCode.QUEUE_UNAVAILABLE));

        queue.issueAfterCommit(petVisit(), QueueType.LABORATORY, 2, CLOCK.instant(), List.of(lid(1L)), "order:1");   // MySQL đã commit

        verify(redis).issue(any(IssueTicketRequest.class));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    // ================================================================== bộ đếm số phiếu mất -> khởi tạo lại từ lịch sử

    @Test
    void issue_whenTheSequenceKeyIsGone_raisesItToTheLargestNumberInTheHistory_beforeIssuing() {
        when(redis.seqExists(java.time.LocalDate.of(2026, 10, 30), QueueType.DOCTOR)).thenReturn(false);
        when(histories.findMaxTicketNo(QueueType.DOCTOR, java.time.LocalDate.of(2026, 10, 30))).thenReturn(7);
        when(redis.issue(any(IssueTicketRequest.class))).thenReturn(new IssuedTicket(tid(60L), 8, false));

        queue.issue(petVisit(), QueueType.DOCTOR, DOCTOR, 0, CLOCK.instant(), null, List.of(), "k", null);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(redis);
        order.verify(redis).ensureSeqAtLeast(java.time.LocalDate.of(2026, 10, 30), QueueType.DOCTOR, 7);   // số mới sẽ là 8, không trùng lịch sử
        order.verify(redis).issue(any(IssueTicketRequest.class));
    }

    @Test
    void issue_whenTheSequenceKeyExists_orThereIsNoHistoryYet_doesNotTouchTheSequence() {
        when(redis.issue(any(IssueTicketRequest.class))).thenReturn(new IssuedTicket(tid(60L), 1, false));
        when(redis.seqExists(any(), any())).thenReturn(true, false);

        queue.issue(petVisit(), QueueType.DOCTOR, DOCTOR, 0, CLOCK.instant(), null, List.of(), "k1", null);   // bộ đếm còn: không cần tra lịch sử
        queue.issue(petVisit(), QueueType.DOCTOR, DOCTOR, 0, CLOCK.instant(), null, List.of(), "k2", null);   // mất bộ đếm nhưng chưa có lịch sử trong ngày

        verify(redis, never()).ensureSeqAtLeast(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(histories, org.mockito.Mockito.times(1)).findMaxTicketNo(any(), any());
    }

    @Test
    void doctorAndRoomKeys_areStableSoEventsReconcileAndRebuildNeverIssueTwoTickets() {
        UUID visit = lid(100L);
        assertThat(QueueCoordinator.doctorKey(visit, false)).isEqualTo("visit:" + visit + ":DOCTOR:FIRST");
        assertThat(QueueCoordinator.doctorKey(visit, true)).isEqualTo("visit:" + visit + ":DOCTOR:RESULTS");
        // khóa phiếu phòng theo dịch vụ có id nhỏ nhất của nhóm, không phụ thuộc thứ tự truyền vào
        assertThat(QueueCoordinator.roomKey(visit, QueueType.LABORATORY, List.of(lid(3L), lid(2L))))
                .isEqualTo(QueueCoordinator.roomKey(visit, QueueType.LABORATORY, List.of(lid(2L), lid(3L))))
                .isEqualTo("visit:" + visit + ":LABORATORY:" + lid(2L));
    }

    @Test
    void startRoomTicketAfterCommit_runsTheStartScript() {
        when(redis.start(tid(70L))).thenReturn(QueueOutcome.NOT_WAITING);   // chỉ ghi log, MySQL đã đúng

        queue.startRoomTicketAfterCommit(tid(70L));

        verify(redis).start(tid(70L));
        doThrow(new AppException(ErrorCode.QUEUE_UNAVAILABLE)).when(redis).start(tid(71L));
        queue.startRoomTicketAfterCommit(tid(71L));   // không ném
    }
}
