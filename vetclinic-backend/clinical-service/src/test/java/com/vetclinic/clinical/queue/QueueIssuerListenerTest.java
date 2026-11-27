package com.vetclinic.clinical.queue;

import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.common.event.ParaclinicalResultsReadyEvent;
import com.vetclinic.common.event.ServiceInvoicePaidEvent;
import com.vetclinic.common.event.VisitCheckedInEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.RECEPTIONIST;
import static com.vetclinic.clinical.testsupport.TestData.detail;
import static com.vetclinic.clinical.testsupport.TestData.lid;
import static com.vetclinic.clinical.testsupport.TestData.service;
import static com.vetclinic.clinical.testsupport.TestData.ticket;
import static com.vetclinic.clinical.testsupport.TestData.uid;
import static com.vetclinic.clinical.testsupport.TestData.visit;
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

/** Phát phiếu từ sự kiện (Quy chuẩn v4.0 mục 9.3): trạng thái ca đọc lại từ MySQL, không tin payload; chống phát trùng nằm ở khóa dedupe của Lua. */
@ExtendWith(MockitoExtension.class)
class QueueIssuerListenerTest {

    private static final UUID VISIT = lid(100L);
    private static final Instant PAID_AT = Instant.parse("2026-10-30T02:30:00Z");
    private static final UUID EVENT = uid(900);

    @Mock
    private QueueCoordinator queue;
    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private VisitServiceDetailRepository detailRepository;

    private QueueIssuerListener listener;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        listener = new QueueIssuerListener(queue, visitRepository, detailRepository, tm);
    }

    private PetVisit visitIn(VisitStatus status, boolean emergency) {
        PetVisit v = visit(100L, status, DOCTOR);
        v.setEmergency(emergency);
        lenient().when(visitRepository.findByIdForUpdate(VISIT)).thenReturn(Optional.of(v));
        return v;
    }

    private static ServiceInvoicePaidEvent.Line line(long detailNo, String code, String category, String queueType) {
        return new ServiceInvoicePaidEvent.Line(uid(500 + detailNo), "SINV-2026-" + detailNo, lid(detailNo), uid(300 + detailNo), code, "Dịch vụ " + code,
                category, queueType, 1, new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100000"));
    }

    private static ServiceInvoicePaidEvent paid(ServiceInvoicePaidEvent.Line... lines) {
        return new ServiceInvoicePaidEvent(EVENT, PAID_AT, RECEPTIONIST, VISIT, "VIS-2026-100", uid(11), "Lu", "Lan", DOCTOR, false, RECEPTIONIST, PAID_AT,
                List.of(lines));
    }

    private void open(TicketView... tickets) {
        lenient().when(queue.openTicketsOf(VISIT)).thenReturn(List.of(tickets));
    }

    // ================================================================== ServiceInvoicePaidEvent

    @Test
    void examFeePaid_issuesADoctorTicket_forTheAssignedDoctor_scoredByThePaymentTime() {
        PetVisit v = visitIn(VisitStatus.WAITING, false);
        open();

        listener.onInvoicePaid(paid(line(1L, "EXAM_GEN", "EXAMINATION", "DOCTOR")));

        verify(queue).issue(eq(v), eq(QueueType.DOCTOR), eq(DOCTOR), eq(0), eq(PAID_AT), eq(RECEPTIONIST), eq(List.of(lid(1L))), eq(QueueCoordinator.doctorKey(VISIT, false)), isNull());
    }

    @Test
    void examFeePaid_afterAnEmergencyAlreadyGotItsTicket_orAfterTheExam_issuesNothing() {
        visitIn(VisitStatus.WAITING, true);
        open(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 2, TicketStatus.WAITING));   // ca cấp cứu đã có phiếu từ lúc tiếp đón
        listener.onInvoicePaid(paid(line(1L, "EXAM_GEN", "EXAMINATION", "DOCTOR")));

        visitIn(VisitStatus.IN_PROGRESS, true);   // phí khám thu SAU khi đã khám
        open();
        listener.onInvoicePaid(paid(line(1L, "EXAM_GEN", "EXAMINATION", "DOCTOR")));

        verify(queue, never()).issue(any(), any(), any(), anyInt(), any(), any(), anyList(), anyString(), any());
    }

    @Test
    void paraclinicalPaid_groupsServicesOfTheSameRoomIntoOneTicket_oneTicketPerRoom() {
        PetVisit v = visitIn(VisitStatus.WAITING_RESULTS, false);
        open();
        ClinicService cbc = service(5L, "LAB_CBC", "LABORATORY", "250000.00");
        ClinicService xray = service(6L, "IMG_XRAY", "IMAGING", "180000.00");
        ClinicService us = service(7L, "IMG_US", "IMAGING", "150000.00");
        when(detailRepository.findAllById(List.of(lid(2L)))).thenReturn(List.of(detail(2L, 100L, cbc, ServiceDetailStatus.PENDING)));
        when(detailRepository.findAllById(List.of(lid(3L), lid(4L)))).thenReturn(List.of(
                detail(3L, 100L, xray, ServiceDetailStatus.PENDING), detail(4L, 100L, us, ServiceDetailStatus.PENDING)));

        listener.onInvoicePaid(paid(line(2L, "LAB_CBC", "LABORATORY", "LABORATORY"), line(3L, "IMG_XRAY", "IMAGING", "IMAGING"),
                line(4L, "IMG_US", "IMAGING", "IMAGING")));

        verify(queue).issue(eq(v), eq(QueueType.LABORATORY), isNull(), eq(0), eq(PAID_AT), eq(RECEPTIONIST), eq(List.of(lid(2L))), eq(QueueCoordinator.roomKey(VISIT, QueueType.LABORATORY, List.of(lid(2L)))), isNull());
        verify(queue).issue(eq(v), eq(QueueType.IMAGING), isNull(), eq(0), eq(PAID_AT), eq(RECEPTIONIST), eq(List.of(lid(3L), lid(4L))), eq(QueueCoordinator.roomKey(VISIT, QueueType.IMAGING, List.of(lid(3L), lid(4L)))), isNull());
    }

    @Test
    void paraclinicalPaid_skipsServicesDoneCancelledOrAlreadyTicketed_andAVisitNoLongerWaitingForResults() {
        visitIn(VisitStatus.WAITING_RESULTS, true);   // cấp cứu: phiếu phòng đã phát lúc chỉ định
        open(ticket(70L, QueueType.LABORATORY, 100L, null, 1, 2, TicketStatus.WAITING, List.of(lid(2L))));
        ClinicService cbc = service(5L, "LAB_CBC", "LABORATORY", "250000.00");
        ClinicService xray = service(6L, "IMG_XRAY", "IMAGING", "180000.00");
        when(detailRepository.findAllById(List.of(lid(2L)))).thenReturn(List.of(detail(2L, 100L, cbc, ServiceDetailStatus.PENDING)));
        when(detailRepository.findAllById(List.of(lid(3L)))).thenReturn(List.of(detail(3L, 100L, xray, ServiceDetailStatus.CANCELLED)));

        listener.onInvoicePaid(paid(line(2L, "LAB_CBC", "LABORATORY", "LABORATORY"), line(3L, "IMG_XRAY", "IMAGING", "IMAGING")));
        visitIn(VisitStatus.RESULTS_READY, false);   // ca đã rời bước chờ kết quả
        open();
        listener.onInvoicePaid(paid(line(2L, "LAB_CBC", "LABORATORY", "LABORATORY")));

        verify(queue, never()).issue(any(), any(), any(), anyInt(), any(), any(), anyList(), anyString(), any());
    }

    @Test
    void emergencyRoomTicketsGetPriorityTwo() {
        PetVisit v = visitIn(VisitStatus.WAITING_RESULTS, true);
        open();
        when(detailRepository.findAllById(List.of(lid(2L)))).thenReturn(List.of(detail(2L, 100L, service(5L, "LAB_CBC", "LABORATORY", "250000.00"), ServiceDetailStatus.PENDING)));

        listener.onInvoicePaid(paid(line(2L, "LAB_CBC", "LABORATORY", "LABORATORY")));

        verify(queue).issue(eq(v), eq(QueueType.LABORATORY), isNull(), eq(2), any(), any(), anyList(), anyString(), isNull());
    }

    @Test
    void inRoomServicesCancelledAndUnknownVisitsGetNoTicket() {
        visitIn(VisitStatus.IN_PROGRESS, false);
        open();
        listener.onInvoicePaid(paid(line(1L, "VAC_7B", "VACCINATION", null)));   // dịch vụ tại phòng: queueType null

        visitIn(VisitStatus.CANCELLED, false);
        listener.onInvoicePaid(paid(line(1L, "EXAM_GEN", "EXAMINATION", "DOCTOR")));   // ca đã hủy

        lenient().when(visitRepository.findByIdForUpdate(VISIT)).thenReturn(Optional.empty());
        listener.onInvoicePaid(paid(line(1L, "EXAM_GEN", "EXAMINATION", "DOCTOR")));   // ca không còn

        verify(queue, never()).issue(any(), any(), any(), anyInt(), any(), any(), anyList(), anyString(), any());
    }

    // ================================================================== VisitCheckedInEvent

    private static VisitCheckedInEvent checkedIn(boolean emergency) {
        return new VisitCheckedInEvent(EVENT, PAID_AT, RECEPTIONIST, VISIT, "VIS-2026-100", uid(11), null, emergency, DOCTOR);
    }

    @Test
    void emergencyCheckIn_issuesAPriorityTwoDoctorTicketImmediately_withoutPayment() {
        PetVisit v = visitIn(VisitStatus.WAITING, true);
        open();
        when(detailRepository.findByVisitIdOrderByIdAsc(VISIT)).thenReturn(List.of(detail(1L, 100L, service(1L, "EXAM_GEN", "EXAMINATION", "100000.00"), ServiceDetailStatus.PENDING)));

        listener.onVisitCheckedIn(checkedIn(true));

        verify(queue).issue(eq(v), eq(QueueType.DOCTOR), eq(DOCTOR), eq(2), eq(PAID_AT), isNull(), eq(List.of()), eq(QueueCoordinator.doctorKey(VISIT, false)), isNull());
    }

    @Test
    void normalCheckIn_waitsForTheExamFee_butAnExamFeeThatIsNotPrepaidGetsATicketAtOnce() {
        PetVisit v = visitIn(VisitStatus.WAITING, false);
        open();
        when(detailRepository.findByVisitIdOrderByIdAsc(VISIT)).thenReturn(List.of(detail(1L, 100L, service(1L, "EXAM_GEN", "EXAMINATION", "100000.00"), ServiceDetailStatus.PENDING)));
        listener.onVisitCheckedIn(checkedIn(false));
        verify(queue, never()).issue(any(), any(), any(), anyInt(), any(), any(), anyList(), anyString(), any());   // thu trước: chờ ServiceInvoicePaidEvent

        ServiceCategory examNoPrepay = ServiceCategory.builder().id((short) 1).code("EXAMINATION").name("Khám bệnh").queueType("DOCTOR").requiresPrepayment(false).build();
        ClinicService exam = ClinicService.builder().id(uid(301)).code("EXAM_GEN").name("Khám").category(examNoPrepay).active(true).build();
        when(detailRepository.findByVisitIdOrderByIdAsc(VISIT)).thenReturn(List.of(detail(1L, 100L, exam, ServiceDetailStatus.PENDING)));
        listener.onVisitCheckedIn(checkedIn(false));
        verify(queue).issue(eq(v), eq(QueueType.DOCTOR), eq(DOCTOR), eq(0), eq(PAID_AT), isNull(), eq(List.of()), eq(QueueCoordinator.doctorKey(VISIT, false)), isNull());
    }

    @Test
    void checkIn_ofACancelledOrAlreadyTicketedVisit_issuesNothing() {
        visitIn(VisitStatus.CANCELLED, true);
        listener.onVisitCheckedIn(checkedIn(true));

        visitIn(VisitStatus.WAITING, true);
        open(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 2, TicketStatus.WAITING));
        when(queue.activeDoctorTicket(VISIT)).thenReturn(Optional.of(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 2, TicketStatus.WAITING)));
        listener.onVisitCheckedIn(checkedIn(true));

        verify(queue, never()).issue(any(), any(), any(), anyInt(), any(), any(), anyList(), anyString(), any());
    }

    // ================================================================== ParaclinicalResultsReadyEvent (T06)

    private static ParaclinicalResultsReadyEvent resultsReady() {
        return new ParaclinicalResultsReadyEvent(EVENT, PAID_AT, null, VISIT, "VIS-2026-100", DOCTOR, uid(11));
    }

    @Test
    void resultsReady_issuesAPriorityOneDoctorTicket_forTheMainDoctor_withoutPayment() {
        PetVisit v = visitIn(VisitStatus.RESULTS_READY, false);

        listener.onResultsReady(resultsReady());

        verify(queue).issue(eq(v), eq(QueueType.DOCTOR), eq(DOCTOR), eq(1), eq(PAID_AT), isNull(), eq(List.of()), eq(QueueCoordinator.doctorKey(VISIT, true)), isNull());
    }

    @Test
    void resultsReady_issuesNothingWhenTheVisitMovedOnOrAlreadyHasADoctorTicket() {
        visitIn(VisitStatus.COMPLETED, false);   // khách bỏ về ngay sau đó
        listener.onResultsReady(resultsReady());

        visitIn(VisitStatus.RESULTS_READY, false);
        when(queue.activeDoctorTicket(VISIT)).thenReturn(Optional.of(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 1, TicketStatus.WAITING)));
        listener.onResultsReady(resultsReady());

        verify(queue, never()).issue(any(), any(), any(), anyInt(), any(), any(), anyList(), anyString(), any());
    }
}
