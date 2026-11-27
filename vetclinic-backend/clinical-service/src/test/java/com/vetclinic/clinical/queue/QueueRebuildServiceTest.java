package com.vetclinic.clinical.queue;

import com.vetclinic.clinical.constant.PaymentStatus;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.PetVisitStatusHistory;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.entity.ServiceInvoice;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.VisitSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.detail;
import static com.vetclinic.clinical.testsupport.TestData.lid;
import static com.vetclinic.clinical.testsupport.TestData.service;
import static com.vetclinic.clinical.testsupport.TestData.ticket;
import static com.vetclinic.clinical.testsupport.TestData.tid;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Dựng lại phiếu trên Redis từ MySQL (Quy chuẩn v4.0 mục 9.3, mục 6): đúng các phiếu còn thiếu, theo thứ tự giờ thu tiền, idempotent. */
@ExtendWith(MockitoExtension.class)
class QueueRebuildServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final Instant NOW = CLOCK.instant();   // 2026-10-30T02:00:00Z
    private static final Instant T_130 = Instant.parse("2026-10-30T01:30:00Z");
    private static final Instant T_140 = Instant.parse("2026-10-30T01:40:00Z");
    private static final Instant T_150 = Instant.parse("2026-10-30T01:50:00Z");

    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private VisitServiceDetailRepository detailRepository;
    @Mock
    private PetVisitStatusHistoryRepository historyRepository;
    @Mock
    private VisitSupport support;
    @Mock
    private QueueCoordinator queue;
    @Mock
    private QueueRedisRepository redis;

    private QueueRebuildService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(queue.issue(any(), any(), any(), anyInt(), any(), any(), anyList(), anyString(), any()))
                .thenAnswer(inv -> new IssuedTicket(UUID.randomUUID(), 1, false));
        lenient().when(visitRepository.findByStatusOrderByIdAsc(any())).thenReturn(List.of());   // mặc định: không có ca ở các trạng thái khác
        service = new QueueRebuildService(visitRepository, detailRepository, historyRepository, support, queue, redis, CLOCK, tm);
    }

    private static ClinicService exam() {
        return service(1L, "EXAM_GEN", "EXAMINATION", "100000.00");
    }

    private static ServiceInvoice invoice(long detailNo, PaymentStatus status, Instant paidAt) {
        return ServiceInvoice.builder().id(lid(500 + detailNo)).visitServiceDetailId(lid(detailNo)).unitPrice(new BigDecimal("100000"))
                .vatRate(BigDecimal.ZERO).paymentStatus(status).paidAt(paidAt).build();
    }

    private void waiting(PetVisit... visits) {
        lenient().when(visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING)).thenReturn(List.of(visits));
    }

    private void details(VisitServiceDetail... details) {
        lenient().when(detailRepository.findByVisitIdInOrderByIdAsc(any())).thenReturn(List.of(details));
    }

    private void invoices(ServiceInvoice... invoices) {
        lenient().when(support.invoicesOf(any())).thenAnswer(inv -> {
            java.util.Map<UUID, ServiceInvoice> map = new java.util.HashMap<>();
            for (ServiceInvoice i : invoices) {
                map.put(i.getVisitServiceDetailId(), i);
            }
            return map;
        });
    }

    // ================================================================== phiếu DOCTOR của ca chờ khám

    @Test
    void waitingVisitWithAPaidExamFee_getsADoctorTicket_scoredByTheTimeOfPayment() {
        PetVisit v = visit(100L, VisitStatus.WAITING, DOCTOR);
        waiting(v);
        details(detail(1L, 100L, exam(), ServiceDetailStatus.PENDING));
        invoices(invoice(1L, PaymentStatus.PAID, T_130));

        QueueRebuildService.Result result = service.rebuild(Duration.ZERO);

        assertThat(result).isEqualTo(new QueueRebuildService.Result(true, 1, 0, 0));
        verify(queue).issue(eq(v), eq(QueueType.DOCTOR), eq(DOCTOR), eq(0), eq(T_130), isNull(), eq(List.of()), eq(QueueCoordinator.doctorKey(lid(100L), false)), isNull());
        verify(redis).markMeta(TODAY, NOW);   // đánh dấu hàng đợi hôm nay đã được dựng
    }

    @Test
    void waitingVisits_unpaidAlreadyTicketed_orWithoutAnExamFee_getNothing() {
        PetVisit unpaid = visit(100L, VisitStatus.WAITING, DOCTOR);
        PetVisit ticketed = visit(101L, VisitStatus.WAITING, DOCTOR);
        PetVisit noExam = visit(102L, VisitStatus.WAITING, DOCTOR);
        waiting(unpaid, ticketed, noExam);
        details(detail(1L, 100L, exam(), ServiceDetailStatus.PENDING), detail(2L, 101L, exam(), ServiceDetailStatus.PENDING));
        invoices(invoice(1L, PaymentStatus.PENDING, null), invoice(2L, PaymentStatus.PAID, T_130));
        lenient().when(queue.activeDoctorTicket(lid(101L))).thenReturn(java.util.Optional.of(ticket(60L, QueueType.DOCTOR, 101L, DOCTOR, 1, 0, TicketStatus.WAITING)));

        QueueRebuildService.Result result = service.rebuild(Duration.ZERO);

        assertThat(result.total()).isZero();   // chưa nộp phí: ở mục "chờ nộp phí"; đã có phiếu: không phát lại (idempotent)
        verify(queue, never()).issue(any(), any(), any(), anyInt(), any(), any(), anyList(), anyString(), any());
    }

    @Test
    void emergencyVisit_getsAPriorityTwoTicketAtCheckInTime_evenWithoutPayment() {
        PetVisit v = visit(100L, VisitStatus.WAITING, DOCTOR);
        v.setEmergency(true);
        waiting(v);
        details(detail(1L, 100L, exam(), ServiceDetailStatus.PENDING));
        invoices(invoice(1L, PaymentStatus.PENDING, null));

        service.rebuild(Duration.ZERO);

        verify(queue).issue(eq(v), eq(QueueType.DOCTOR), eq(DOCTOR), eq(2), eq(v.getCheckedInAt()), isNull(), eq(List.of()), eq(QueueCoordinator.doctorKey(lid(100L), false)), isNull());
    }

    @Test
    void examFeeThatNeedsNoPrepayment_getsATicketAtCheckInTime() {
        PetVisit v = visit(100L, VisitStatus.WAITING, DOCTOR);
        waiting(v);
        ServiceCategory freeExam = ServiceCategory.builder().id((short) 1).code("EXAMINATION").name("Khám bệnh").queueType("DOCTOR").requiresPrepayment(false).build();
        ClinicService exam = ClinicService.builder().id(lid(900)).code("EXAM_GEN").name("Khám").category(freeExam).active(true).build();
        details(detail(1L, 100L, exam, ServiceDetailStatus.PENDING));
        invoices(invoice(1L, PaymentStatus.PENDING, null));

        assertThat(service.rebuild(Duration.ZERO).doctorTickets()).isEqualTo(1);
        verify(queue).issue(eq(v), eq(QueueType.DOCTOR), eq(DOCTOR), eq(0), eq(v.getCheckedInAt()), isNull(), eq(List.of()), anyString(), isNull());
    }

    @Test
    void visitsAreIssuedInPaymentOrder_soTheTicketNumbersFollowTheTimeOfPayment() {
        PetVisit later = visit(100L, VisitStatus.WAITING, DOCTOR);
        PetVisit earlier = visit(101L, VisitStatus.WAITING, DOCTOR);
        waiting(later, earlier);   // theo id: ca 100 trước, nhưng ca 101 thu phí sớm hơn
        details(detail(1L, 100L, exam(), ServiceDetailStatus.PENDING), detail(2L, 101L, exam(), ServiceDetailStatus.PENDING));
        invoices(invoice(1L, PaymentStatus.PAID, T_150), invoice(2L, PaymentStatus.PAID, T_130));

        service.rebuild(Duration.ZERO);

        InOrder order = Mockito.inOrder(queue);
        order.verify(queue).issue(eq(earlier), any(), any(), anyInt(), eq(T_130), any(), anyList(), anyString(), any());
        order.verify(queue).issue(eq(later), any(), any(), anyInt(), eq(T_150), any(), anyList(), anyString(), any());
    }

    @Test
    void minAge_leavesRecentPaymentsAloneBecauseTheirEventsAreStillOnTheWay() {
        PetVisit v = visit(100L, VisitStatus.WAITING, DOCTOR);
        waiting(v);
        details(detail(1L, 100L, exam(), ServiceDetailStatus.PENDING));
        invoices(invoice(1L, PaymentStatus.PAID, T_150));   // thu cách đây 10 phút

        assertThat(service.rebuild(Duration.ofMinutes(30)).total()).isZero();   // mới hơn mức tuổi tối thiểu: sự kiện còn đang tới
        verify(queue, never()).issue(any(), any(), any(), anyInt(), any(), any(), anyList(), anyString(), any());
        assertThat(service.rebuild(Duration.ofMinutes(5)).total()).isEqualTo(1);
    }

    @Test
    void ticketsAlreadyOnRedis_areCountedAsAlreadyIssued_notDuplicated() {
        PetVisit v = visit(100L, VisitStatus.WAITING, DOCTOR);
        waiting(v);
        details(detail(1L, 100L, exam(), ServiceDetailStatus.PENDING));
        invoices(invoice(1L, PaymentStatus.PAID, T_130));
        when(queue.issue(any(), any(), any(), anyInt(), any(), any(), anyList(), anyString(), any())).thenReturn(new IssuedTicket(tid(60L), 3, true));

        QueueRebuildService.Result result = service.rebuild(Duration.ZERO);

        assertThat(result).isEqualTo(new QueueRebuildService.Result(true, 0, 0, 1));   // khóa dedupe đã có: không phát thêm
    }

    // ================================================================== phiếu DOCTOR ưu tiên của ca có kết quả

    @Test
    void resultsReadyVisit_getsAPriorityOneTicket_atTheTimeTheResultsWereComplete() {
        PetVisit v = visit(100L, VisitStatus.RESULTS_READY, DOCTOR);
        lenient().when(visitRepository.findByStatusOrderByIdAsc(VisitStatus.RESULTS_READY)).thenReturn(List.of(v));
        when(historyRepository.findByVisitIdOrderByIdAsc(lid(100L))).thenReturn(List.of(
                PetVisitStatusHistory.builder().visitId(lid(100L)).toStatus(VisitStatus.WAITING_RESULTS).createdAt(T_130).build(),
                PetVisitStatusHistory.builder().visitId(lid(100L)).toStatus(VisitStatus.RESULTS_READY).createdAt(T_140).build()));

        QueueRebuildService.Result result = service.rebuild(Duration.ZERO);

        assertThat(result.doctorTickets()).isEqualTo(1);
        verify(queue).issue(eq(v), eq(QueueType.DOCTOR), eq(DOCTOR), eq(1), eq(T_140), isNull(), eq(List.of()), eq(QueueCoordinator.doctorKey(lid(100L), true)), isNull());
    }

    // ================================================================== phiếu phòng XN / CĐHA

    @Test
    void paidParaclinicalServices_waitingForResults_getOneTicketPerRoom_skippingTicketedUnpaidAndInRoomOnes() {
        PetVisit v = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        lenient().when(visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING_RESULTS)).thenReturn(List.of(v));
        ClinicService cbc = service(5L, "LAB_CBC", "LABORATORY", "250000.00");
        ClinicService xray = service(6L, "IMG_XRAY", "IMAGING", "180000.00");
        ClinicService us = service(7L, "IMG_US", "IMAGING", "150000.00");
        ClinicService vaccine = service(8L, "VAC_7B", "VACCINATION", "150000.00");
        details(detail(2L, 100L, cbc, ServiceDetailStatus.PENDING),       // đã thu -> phiếu Lab
                detail(3L, 100L, xray, ServiceDetailStatus.PENDING),      // đã thu -> phiếu CĐHA
                detail(4L, 100L, us, ServiceDetailStatus.PENDING),        // đã thu -> cùng phiếu CĐHA
                detail(5L, 100L, cbc, ServiceDetailStatus.PENDING),       // chưa thu -> chưa có phiếu
                detail(6L, 100L, xray, ServiceDetailStatus.PENDING),      // đã có phiếu mở -> bỏ qua
                detail(7L, 100L, us, ServiceDetailStatus.IN_PROGRESS),    // đang làm -> bỏ qua
                detail(9L, 100L, vaccine, ServiceDetailStatus.PENDING));  // dịch vụ tại phòng -> không có phiếu
        invoices(invoice(2L, PaymentStatus.PAID, T_140), invoice(3L, PaymentStatus.PAID, T_130), invoice(4L, PaymentStatus.PAID, T_150),
                invoice(5L, PaymentStatus.PENDING, null), invoice(6L, PaymentStatus.PAID, T_130), invoice(7L, PaymentStatus.PAID, T_130));
        when(queue.openTicketsOf(lid(100L))).thenReturn(List.of(ticket(70L, QueueType.IMAGING, 100L, null, 1, 0, TicketStatus.WAITING, List.of(lid(6L)))));

        QueueRebuildService.Result result = service.rebuild(Duration.ZERO);

        assertThat(result).isEqualTo(new QueueRebuildService.Result(true, 0, 2, 0));
        verify(queue).issue(eq(v), eq(QueueType.LABORATORY), isNull(), eq(0), eq(T_140), isNull(), eq(List.of(lid(2L))),
                eq(QueueCoordinator.roomKey(lid(100L), QueueType.LABORATORY, List.of(lid(2L)))), isNull());
        verify(queue).issue(eq(v), eq(QueueType.IMAGING), isNull(), eq(0), eq(T_130), isNull(), eq(List.of(lid(3L), lid(4L))),   // giờ của phiếu = giờ thu sớm nhất
                eq(QueueCoordinator.roomKey(lid(100L), QueueType.IMAGING, List.of(lid(3L), lid(4L)))), isNull());
    }

    @Test
    void emergencyVisit_unpaidParaclinicalServices_getRoomTicketsWithPriorityTwo() {
        PetVisit v = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        v.setEmergency(true);
        lenient().when(visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING_RESULTS)).thenReturn(List.of(v));
        VisitServiceDetail d = detail(2L, 100L, service(5L, "LAB_CBC", "LABORATORY", "250000.00"), ServiceDetailStatus.PENDING);
        details(d);
        invoices(invoice(2L, PaymentStatus.PENDING, null));   // khám trước, thu sau

        service.rebuild(Duration.ZERO);

        verify(queue).issue(eq(v), eq(QueueType.LABORATORY), isNull(), eq(2), eq(d.getCreatedAt()), isNull(), eq(List.of(lid(2L))), anyString(), isNull());
    }

    // ================================================================== khác

    @Test
    void metaPresent_asksRedisAboutTodaysMetaKey() {
        when(redis.metaPresent(TODAY)).thenReturn(true, false);

        assertThat(service.metaPresent()).isTrue();
        assertThat(service.metaPresent()).isFalse();
    }

    @Test
    void nothingToRebuild_stillMarksTheDayAsBuilt_soTheStartupJobDoesNotRepeatIt() {
        QueueRebuildService.Result result = service.rebuild(Duration.ZERO);

        assertThat(result).isEqualTo(new QueueRebuildService.Result(true, 0, 0, 0));
        verify(redis).markMeta(TODAY, NOW);
        assertThat(Map.of()).isEmpty();
    }
}
