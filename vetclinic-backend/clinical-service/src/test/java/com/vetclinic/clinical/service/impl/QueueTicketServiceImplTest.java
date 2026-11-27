package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.ServiceDetailAction;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.QueueTicketResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.queue.CallResult;
import com.vetclinic.clinical.queue.QueueCoordinator;
import com.vetclinic.clinical.queue.QueueOutcome;
import com.vetclinic.clinical.queue.QueueRedisRepository;
import com.vetclinic.clinical.queue.TicketView;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.PatientCalledEvent;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.IMAGING_TECH;
import static com.vetclinic.clinical.testsupport.TestData.LAB_TECH;
import static com.vetclinic.clinical.testsupport.TestData.OTHER_DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.detail;
import static com.vetclinic.clinical.testsupport.TestData.lid;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.service;
import static com.vetclinic.clinical.testsupport.TestData.ticket;
import static com.vetclinic.clinical.testsupport.TestData.tid;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BR-34 / SM-07 (Redis, bước 6.2): "Gọi" chạy call_ticket.lua (chỉ tăng calledCount + ghi lastCalledAt) và phát thông báo, không đổi trạng thái hay thứ tự;
 * "Bắt đầu" phiếu phòng đổi dịch vụ sang đang thực hiện trong một giao dịch MySQL rồi chạy start_ticket.lua sau commit.
 */
@ExtendWith(MockitoExtension.class)
class QueueTicketServiceImplTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);   // CLOCK cố định
    private static final ClinicService CBC = service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00");
    private static final ClinicService XRAY = service(6L, "IMG_XRAY", ServiceCategory.IMAGING, "180000.00");

    @Mock
    private QueueRedisRepository redis;
    @Mock
    private QueueCoordinator queue;
    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private VisitServiceDetailRepository detailRepository;
    @Mock
    private VisitSupport support;
    @Mock
    private AuthClient authClient;
    @Mock
    private ApplicationEventPublisher publisher;

    private QueueTicketServiceImpl service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new QueueTicketServiceImpl(redis, queue, visitRepository, detailRepository, support, authClient, publisher, CLOCK, tm);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private void stubRoom(UUID userId, RoleCode role, String room) {
        lenient().when(authClient.getUser(userId)).thenReturn(ApiResponse.success(
                new UserSummaryResponse(userId, "User " + userId, "0900000000", role, List.of(role), room, "ACTIVE")));
    }

    private PatientCalledEvent publishedCall() {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        return (PatientCalledEvent) event.getValue();
    }

    // ================================================================== Gọi

    @Test
    void callNext_callsTheHeadOfTheDoctorsRedisQueue_withoutChangingItsStatusOrOrder() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubRoom(DOCTOR, RoleCode.VETERINARIAN, "P101");
        TicketView head = ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 4, 0, TicketStatus.WAITING);
        TicketView next = ticket(61L, QueueType.DOCTOR, 101L, DOCTOR, 5, 0, TicketStatus.WAITING);
        when(redis.waitingViews(TODAY, QueueType.DOCTOR, DOCTOR)).thenReturn(List.of(head, next));
        when(redis.call(tid(60L))).thenReturn(new CallResult(QueueOutcome.OK, 1));
        when(visitRepository.findWithPetById(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING, DOCTOR)));

        QueueTicketResponse called = service.callNext(QueueType.DOCTOR);

        assertThat(called.id()).isEqualTo(tid(60L));
        assertThat(called.ticketNo()).isEqualTo(4);
        assertThat(called.roomNumber()).isEqualTo("P101");
        assertThat(called.status()).isEqualTo(TicketStatus.WAITING);   // gọi trước, đổi trạng thái sau ("Bắt đầu khám")
        assertThat(called.lastCalledAt()).isEqualTo(CLOCK.instant());
        verify(redis, never()).call(tid(61L));   // chỉ gọi phiếu đầu hàng
        PatientCalledEvent event = publishedCall();
        assertThat(event.action()).isEqualTo("CALLED");
        assertThat(event.station()).isEqualTo("DOCTOR_ROOM");
        assertThat(event.ticketNo()).isEqualTo(4);
        assertThat(event.ticketId()).isEqualTo(tid(60L));
        assertThat(event.visitId()).isEqualTo(lid(100L));
    }

    @Test
    void callNext_emptyQueue_CLIN_1303_whileExamining_CLIN_1302_andWrongRoleIsDenied() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(redis.waitingViews(TODAY, QueueType.DOCTOR, DOCTOR)).thenReturn(List.of());
        assertError(ErrorCode.QUEUE_EMPTY, () -> service.callNext(QueueType.DOCTOR));

        when(visitRepository.existsByDoctorIdAndStatus(DOCTOR, VisitStatus.IN_PROGRESS)).thenReturn(true);   // kể cả khi hàng đợi trống
        assertError(ErrorCode.DOCTOR_NOT_AVAILABLE, () -> service.callNext(QueueType.DOCTOR));

        // Bác sĩ không gọi hàng đợi phòng; KTV Lab không gọi hàng đợi CĐHA
        assertError(ErrorCode.ACCESS_DENIED, () -> service.callNext(QueueType.LABORATORY));
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.callNext(QueueType.IMAGING));
        verify(publisher, never()).publishEvent(any(Object.class));
        verify(redis, never()).call(any());
    }

    @Test
    void callNext_atTheLabCallsTheSharedRoomQueue() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubRoom(LAB_TECH, RoleCode.LAB_TECHNICIAN, "P201");
        TicketView head = ticket(70L, QueueType.LABORATORY, 102L, null, 1, 0, TicketStatus.WAITING);
        when(redis.waitingViews(TODAY, QueueType.LABORATORY, null)).thenReturn(List.of(head));   // hàng đợi phòng dùng ALL, không theo Bác sĩ
        when(redis.call(tid(70L))).thenReturn(new CallResult(QueueOutcome.OK, 1));
        when(visitRepository.findWithPetById(lid(102L))).thenReturn(Optional.of(visit(102L, VisitStatus.WAITING_RESULTS, DOCTOR)));

        assertThat(service.callNext(QueueType.LABORATORY).ticketNo()).isEqualTo(1);
        assertThat(publishedCall().station()).isEqualTo("LAB");
    }

    @Test
    void call_aTicketInTheMiddle_whenTheHeadIsAbsent_onlyAnnounces_theOrderNeverChanges() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubRoom(LAB_TECH, RoleCode.LAB_TECHNICIAN, "P201");
        TicketView third = ticket(72L, QueueType.LABORATORY, 102L, null, 3, 0, TicketStatus.WAITING);
        when(redis.view(tid(72L))).thenReturn(Optional.of(third));
        when(redis.call(tid(72L))).thenReturn(new CallResult(QueueOutcome.OK, 2));   // "Gọi lại" lần hai
        when(visitRepository.findWithPetById(lid(102L))).thenReturn(Optional.of(visit(102L, VisitStatus.WAITING_RESULTS, DOCTOR)));

        QueueTicketResponse called = service.call(tid(72L));

        assertThat(called.ticketNo()).isEqualTo(3);
        assertThat(called.status()).isEqualTo(TicketStatus.WAITING);
        assertThat(called.priority()).isZero();
        assertThat(called.issuedAt()).isEqualTo(third.issuedAt());
        PatientCalledEvent event = publishedCall();
        assertThat(event.station()).isEqualTo("LAB");
        assertThat(event.roomNumber()).isEqualTo("P201");
        assertThat(event.ticketId()).isEqualTo(tid(72L));
    }

    @Test
    void call_isRefusedForAnotherDoctorsTicket_aTicketNotWaiting_andAnUnknownTicket() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(redis.view(tid(60L))).thenReturn(Optional.of(ticket(60L, QueueType.DOCTOR, 100L, OTHER_DOCTOR, 1, 0, TicketStatus.WAITING)));
        when(redis.view(tid(61L))).thenReturn(Optional.of(ticket(61L, QueueType.DOCTOR, 101L, DOCTOR, 2, 0, TicketStatus.IN_SERVICE)));
        when(redis.view(tid(99L))).thenReturn(Optional.empty());

        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.call(tid(60L)));
        assertError(ErrorCode.TICKET_NOT_WAITING, () -> service.call(tid(61L)));
        assertError(ErrorCode.TICKET_NOT_FOUND, () -> service.call(tid(99L)));
        verify(publisher, never()).publishEvent(any(Object.class));
        verify(redis, never()).call(any());
    }

    @Test
    void call_aTicketThatVanishedOrWasStartedBetweenTheReadAndTheLuaScript_isRefused() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(redis.view(tid(60L))).thenReturn(Optional.of(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 0, TicketStatus.WAITING)));
        when(redis.call(tid(60L))).thenReturn(new CallResult(QueueOutcome.NOT_WAITING, 0), new CallResult(QueueOutcome.NOT_FOUND, 0));

        assertError(ErrorCode.TICKET_NOT_WAITING, () -> service.call(tid(60L)));
        assertError(ErrorCode.TICKET_NOT_FOUND, () -> service.call(tid(60L)));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    // ================================================================== Bắt đầu phiếu phòng

    @Test
    void start_aRoomTicket_startsEveryPendingServiceOfIt_thenRunsTheLuaScriptAfterCommit() {
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        VisitServiceDetail us = detail(1L, 100L, XRAY, ServiceDetailStatus.PENDING);
        VisitServiceDetail xray = detail(2L, 100L, XRAY, ServiceDetailStatus.PENDING);
        TicketView t = ticket(80L, QueueType.IMAGING, 100L, null, 1, 0, TicketStatus.WAITING, List.of(lid(2L), lid(1L)));
        PetVisit visit = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        when(redis.view(tid(80L))).thenReturn(Optional.of(t));
        when(detailRepository.findByIdForUpdate(lid(1L))).thenReturn(Optional.of(us));
        when(detailRepository.findByIdForUpdate(lid(2L))).thenReturn(Optional.of(xray));
        when(support.lock(lid(100L))).thenReturn(visit);
        when(visitRepository.findWithPetById(lid(100L))).thenReturn(Optional.of(visit));

        QueueTicketResponse started = service.start(tid(80L));

        assertThat(started.status()).isEqualTo(TicketStatus.IN_SERVICE);
        assertThat(us.getStatus()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);
        assertThat(xray.getStatus()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);
        verify(support).recordDetail(lid(1L), ServiceDetailStatus.PENDING, ServiceDetailStatus.IN_PROGRESS, ServiceDetailAction.START_PROCESSING, IMAGING_TECH, null);
        verify(support).recordDetail(lid(2L), ServiceDetailStatus.PENDING, ServiceDetailStatus.IN_PROGRESS, ServiceDetailAction.START_PROCESSING, IMAGING_TECH, null);
        verify(queue).startRoomTicketAfterCommit(tid(80L));
    }

    @Test
    void start_isRefusedForADoctorTicket_theWrongRoom_aTicketAlreadyStarted_aSecondConcurrentStart_andAVisitNoLongerWaiting() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        when(redis.view(tid(60L))).thenReturn(Optional.of(ticket(60L, QueueType.DOCTOR, 100L, DOCTOR, 1, 0, TicketStatus.WAITING)));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.start(tid(60L)));   // phiếu Bác sĩ bắt đầu bằng start-exam

        when(redis.view(tid(80L))).thenReturn(Optional.of(ticket(80L, QueueType.IMAGING, 100L, null, 1, 0, TicketStatus.WAITING)));
        assertError(ErrorCode.ACCESS_DENIED, () -> service.start(tid(80L)));        // KTV Lab không bắt đầu phiếu CĐHA

        // phiếu đã IN_SERVICE trên Redis
        when(redis.view(tid(70L))).thenReturn(Optional.of(ticket(70L, QueueType.LABORATORY, 101L, null, 1, 0, TicketStatus.IN_SERVICE, List.of(lid(3L)))));
        assertError(ErrorCode.TICKET_NOT_WAITING, () -> service.start(tid(70L)));

        // hai người cùng bấm: Redis còn thấy WAITING nhưng người thắng đã đổi dịch vụ sang IN_PROGRESS -> người thua nhận CLIN_1313
        when(redis.view(tid(71L))).thenReturn(Optional.of(ticket(71L, QueueType.LABORATORY, 101L, null, 2, 0, TicketStatus.WAITING, List.of(lid(3L)))));
        when(detailRepository.findByIdForUpdate(lid(3L))).thenReturn(Optional.of(detail(3L, 101L, CBC, ServiceDetailStatus.IN_PROGRESS)));
        when(support.lock(lid(101L))).thenReturn(visit(101L, VisitStatus.WAITING_RESULTS, DOCTOR));
        assertError(ErrorCode.TICKET_NOT_WAITING, () -> service.start(tid(71L)));

        when(redis.view(tid(72L))).thenReturn(Optional.of(ticket(72L, QueueType.LABORATORY, 102L, null, 3, 0, TicketStatus.WAITING, List.of(lid(4L)))));
        when(detailRepository.findByIdForUpdate(lid(4L))).thenReturn(Optional.of(detail(4L, 102L, CBC, ServiceDetailStatus.PENDING)));
        when(support.lock(lid(102L))).thenReturn(visit(102L, VisitStatus.COMPLETED, DOCTOR));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.start(tid(72L)));
        verify(support, never()).recordDetail(any(), any(), any(), any(), any(), any());
        verify(queue, never()).startRoomTicketAfterCommit(any());
    }
}
