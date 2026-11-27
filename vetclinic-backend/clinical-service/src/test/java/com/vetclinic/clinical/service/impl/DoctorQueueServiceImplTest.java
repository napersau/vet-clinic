package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.DoctorStatus;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.DoctorQueueResponse;
import com.vetclinic.clinical.dto.response.QueueBoardResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.queue.QueueRedisRepository;
import com.vetclinic.clinical.queue.TicketView;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.clinical.repository.ImagingAttachmentRepository;
import com.vetclinic.clinical.repository.ImagingResultRepository;
import com.vetclinic.clinical.repository.LabTestResultRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import com.vetclinic.clinical.repository.ServiceInvoiceRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.VisitTimeline;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.UUID;
import java.util.List;
import java.util.Set;

import static com.vetclinic.clinical.testsupport.TestData.lid;
import static com.vetclinic.clinical.testsupport.TestData.uid;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.OTHER_DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.ticket;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Hàng đợi Bác sĩ đọc từ phiếu xếp hàng DOCTOR (17/11/2026): ca chưa nộp phí khám không vào hàng đợi mà ở mục "chờ thu phí". */
@ExtendWith(MockitoExtension.class)
class DoctorQueueServiceImplTest {

    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private QueueRedisRepository redis;
    @Mock
    private VisitSupport support;
    @Mock
    private AuthClient authClient;

    private DoctorQueueServiceImpl service;

    @BeforeEach
    void setUp() {
        VisitAssembler assembler = new VisitAssembler(mock(VisitServiceDetailRepository.class), mock(LabTestResultRepository.class),
                mock(ImagingResultRepository.class), mock(ImagingAttachmentRepository.class), mock(ServiceInvoiceRepository.class),
                new VisitTimeline(mock(PetVisitStatusHistoryRepository.class)), mock(com.vetclinic.clinical.queue.QueueCoordinator.class),
                mock(com.vetclinic.clinical.repository.VitalSignRepository.class), mock(com.vetclinic.clinical.repository.MedicalRecordRepository.class));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new DoctorQueueServiceImpl(visitRepository, redis, support, authClient, assembler, com.vetclinic.clinical.testsupport.TestData.CLOCK, tm);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static UserSummaryResponse doctor(UUID id, String room) {
        return new UserSummaryResponse(id, "BS " + id, "0911111111", RoleCode.VETERINARIAN, List.of(RoleCode.VETERINARIAN), room, "ACTIVE");
    }

    private static final java.time.LocalDate TODAY = java.time.LocalDate.of(2026, 10, 30);   // CLOCK cố định

    private static TicketView doctorTicket(long id, long visitId, UUID doctorId, int no, int priority) {
        return ticket(id, QueueType.DOCTOR, visitId, doctorId, no, priority, TicketStatus.WAITING);
    }

    @Test
    void board_hasAwaitingPayment_theUnassignedTickets_andOneTicketQueuePerDoctor_withASuggestion() {
        when(authClient.getActiveDoctors()).thenReturn(ApiResponse.success(List.of(
                doctor(DOCTOR, "P101"), doctor(OTHER_DOCTOR, "P102"))));
        // Phiếu chưa có Bác sĩ (đã nộp phí, chờ Lễ tân phân công)
        when(redis.waitingViews(TODAY, QueueType.DOCTOR, null)).thenReturn(List.of(doctorTicket(61L, 1L, null, 1, 0)));
        // Hàng đợi DOCTOR (Sorted Set theo score): phiếu ưu tiên của ca có kết quả (priority 1) đứng trước phiếu thường; OTHER_DOCTOR trống
        when(redis.waitingViews(TODAY, QueueType.DOCTOR, DOCTOR)).thenReturn(List.of(
                doctorTicket(63L, 3L, DOCTOR, 3, 1), doctorTicket(62L, 4L, DOCTOR, 2, 0)));
        when(redis.waitingViews(TODAY, QueueType.DOCTOR, OTHER_DOCTOR)).thenReturn(List.of());
        when(visitRepository.findDoctorIdsWithStatusIn(List.of(VisitStatus.IN_PROGRESS))).thenReturn(Set.of(DOCTOR));
        // Ca 1, 4, 5 đang WAITING; ca 5 còn hóa đơn phí khám PENDING (MySQL) -> chờ thu phí khám
        when(visitRepository.findByStatusOrderByIdAsc(VisitStatus.WAITING)).thenReturn(List.of(
                visit(1L, VisitStatus.WAITING, null), visit(4L, VisitStatus.WAITING, DOCTOR), visit(5L, VisitStatus.WAITING, null)));
        when(support.visitsAwaitingExamFee(anyCollection())).thenReturn(Set.of(lid(5L)));
        when(visitRepository.findWithPetByIdIn(anyCollection())).thenReturn(List.of(
                visit(1L, VisitStatus.WAITING, null), visit(3L, VisitStatus.RESULTS_READY, DOCTOR), visit(4L, VisitStatus.WAITING, DOCTOR)));

        QueueBoardResponse board = service.board();

        assertThat(board.awaitingPayment()).extracting(VisitResponse::id).containsExactly(lid(5L));
        assertThat(board.awaitingPayment().get(0).ticket()).isNull();
        assertThat(board.unassigned()).extracting(VisitResponse::id).containsExactly(lid(1L));
        assertThat(board.unassigned().get(0).ticket().ticketNo()).isEqualTo(1);
        DoctorQueueResponse first = board.doctors().get(0);
        assertThat(first.doctorId()).isEqualTo(DOCTOR);
        assertThat(first.status()).isEqualTo(DoctorStatus.BUSY);
        assertThat(first.waitingCount()).isEqualTo(2);
        assertThat(first.visits()).extracting(VisitResponse::id).containsExactly(lid(3L), lid(4L));
        assertThat(first.visits()).extracting(v -> v.ticket().ticketNo()).containsExactly(3, 2);
        assertThat(first.visits()).extracting(VisitResponse::assignedRoom).containsOnly("P101");
        DoctorQueueResponse second = board.doctors().get(1);
        assertThat(second.status()).isEqualTo(DoctorStatus.AVAILABLE);
        assertThat(second.waitingCount()).isZero();
        assertThat(board.suggestedDoctorId()).isEqualTo(OTHER_DOCTOR);   // ít phiếu chờ nhất
    }

    @Test
    void suggest_prefersTheFewestWaiting_thenAnAvailableDoctor_thenTheLowestId() {
        var busy = new DoctorQueueResponse(uid(1), "A", "P1", DoctorStatus.BUSY, 0, List.of());
        var free = new DoctorQueueResponse(uid(2), "B", "P2", DoctorStatus.AVAILABLE, 0, List.of());
        var longer = new DoctorQueueResponse(uid(3), "C", "P3", DoctorStatus.AVAILABLE, 4, List.of());

        assertThat(DoctorQueueServiceImpl.suggest(List.of(busy, free, longer))).isEqualTo(uid(2));   // hòa số chờ: ưu tiên bác sĩ rảnh
        assertThat(DoctorQueueServiceImpl.suggest(List.of(longer, busy))).isEqualTo(uid(1));          // ít phiếu chờ hơn thắng dù đang bận
        assertThat(DoctorQueueServiceImpl.suggest(List.of())).isNull();
    }

    @Test
    void board_failsAsServiceUnavailableWhenAuthReturnsNothing() {
        when(authClient.getActiveDoctors()).thenReturn(null);

        assertError(ErrorCode.SERVICE_UNAVAILABLE, () -> service.board());
    }

    @Test
    void myQueue_isTheCurrentDoctorsWaitingTickets_inTicketOrder() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(redis.waitingViews(TODAY, QueueType.DOCTOR, DOCTOR)).thenReturn(List.of(
                doctorTicket(70L, 9L, DOCTOR, 7, 2), doctorTicket(63L, 3L, DOCTOR, 3, 1), doctorTicket(62L, 4L, DOCTOR, 2, 0)));
        when(visitRepository.findWithPetByIdIn(anyCollection())).thenReturn(List.of(
                visit(3L, VisitStatus.RESULTS_READY, DOCTOR), visit(4L, VisitStatus.WAITING, DOCTOR), visit(9L, VisitStatus.WAITING, DOCTOR)));

        List<VisitResponse> queue = service.myQueue();

        // cấp cứu (2) -> có kết quả (1) -> thường (0), dù số phiếu không theo thứ tự đó
        assertThat(queue).extracting(VisitResponse::id).containsExactly(lid(9L), lid(3L), lid(4L));
        assertThat(queue).extracting(v -> v.ticket().priority()).containsExactly(2, 1, 0);
    }
}
