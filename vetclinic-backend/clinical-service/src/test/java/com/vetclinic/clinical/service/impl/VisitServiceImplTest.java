package com.vetclinic.clinical.service.impl;

import com.vetclinic.common.event.DomainEvent;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.clinical.constant.ServiceKind;
import com.vetclinic.clinical.client.AppointmentClient;
import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.PaymentStatus;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.request.AssignDoctorRequest;
import com.vetclinic.clinical.dto.request.CheckInRequest;
import com.vetclinic.clinical.dto.request.CloseVisitRequest;
import com.vetclinic.clinical.dto.request.OrderServicesRequest;
import com.vetclinic.clinical.dto.request.ParaclinicalOrderRequest;
import com.vetclinic.clinical.dto.request.ReasonRequest;
import com.vetclinic.clinical.dto.request.ServiceOrderItem;
import com.vetclinic.clinical.dto.request.StartExamRequest;
import com.vetclinic.clinical.dto.request.UpdateMedicalRecordRequest;
import com.vetclinic.clinical.dto.request.VitalSignsRequest;
import com.vetclinic.clinical.dto.response.MedicalRecordResponse;
import com.vetclinic.clinical.dto.response.VitalSignResponse;
import com.vetclinic.clinical.entity.MedicalRecord;
import com.vetclinic.clinical.entity.VitalSign;
import com.vetclinic.clinical.repository.MedicalRecordRepository;
import com.vetclinic.clinical.repository.PrescriptionRepository;
import com.vetclinic.clinical.repository.VitalSignRepository;
import com.vetclinic.clinical.dto.response.AppointmentSummaryResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.PetVisitStatusHistory;
import com.vetclinic.clinical.entity.ServiceInvoice;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.ClinicServiceRepository;
import com.vetclinic.clinical.repository.ImagingAttachmentRepository;
import com.vetclinic.clinical.repository.ImagingResultRepository;
import com.vetclinic.clinical.repository.LabTestResultRepository;
import com.vetclinic.clinical.repository.PetRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import com.vetclinic.clinical.queue.QueueCoordinator;
import com.vetclinic.common.event.VisitCancelledEvent;
import com.vetclinic.clinical.repository.ServiceInvoiceRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailHistoryRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.clinical.service.VisitTimeline;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.lock.NamedLockService;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.function.Supplier;

import static com.vetclinic.clinical.testsupport.TestData.uid;
import static com.vetclinic.clinical.testsupport.TestData.lid;
import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.OWNER;
import static com.vetclinic.clinical.testsupport.TestData.OTHER_DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.RECEPTIONIST;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.detail;
import static com.vetclinic.clinical.testsupport.TestData.dog;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.pet;
import static com.vetclinic.clinical.testsupport.TestData.service;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static com.vetclinic.clinical.testsupport.TestData.pid;
import static com.vetclinic.clinical.testsupport.TestData.apid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisitServiceImplTest {

    private static final UUID ADMIN_ID = uid(1);
    private static final ClinicService EXAM_SVC = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00");

    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private PetRepository petRepository;
    @Mock
    private PetVisitStatusHistoryRepository historyRepository;
    @Mock
    private VisitServiceDetailRepository detailRepository;
    @Mock
    private VisitServiceDetailHistoryRepository detailHistoryRepository;
    @Mock
    private VitalSignRepository vitalSignRepository;
    @Mock
    private MedicalRecordRepository medicalRecordRepository;
    @Mock
    private PrescriptionRepository prescriptionRepository;
    @Mock
    private ClinicServiceRepository serviceRepository;
    @Mock
    private ServiceInvoiceRepository invoiceRepository;
    @Mock
    private AuthClient authClient;
    @Mock
    private AppointmentClient appointmentClient;
    @Mock
    private NamedLockService lockService;
    @Mock
    private OutboxWriter outboxWriter;
    @Mock
    private QueueCoordinator ticketIssuer;   // phiếu xếp hàng: Redis qua QueueCoordinator (bước 6.2)

    private VisitServiceImpl service;
    /** Đợt khám đã có lần đo đủ cân nặng + thân nhiệt (dùng cho vitalSignRepository.existsCompleteMeasurement). */
    private final java.util.Set<UUID> completeMeasurements = new java.util.HashSet<>();

    @BeforeEach
    void setUp() {
        VisitSupport support = new VisitSupport(visitRepository, historyRepository, detailRepository,
                detailHistoryRepository, invoiceRepository, com.vetclinic.clinical.testsupport.TestData.priceBook(), outboxWriter, CLOCK);
        VisitAssembler assembler = new VisitAssembler(detailRepository, mock(LabTestResultRepository.class),
                mock(ImagingResultRepository.class), mock(ImagingAttachmentRepository.class), invoiceRepository,
                new VisitTimeline(historyRepository),
                ticketIssuer, vitalSignRepository, medicalRecordRepository);
        // Mỗi dòng chỉ định được lập hóa đơn riêng ngay khi tạo (BR-19): giả lập INSERT hóa đơn có id
        lenient().when(invoiceRepository.saveAndFlush(any(ServiceInvoice.class))).thenAnswer(inv -> {
            ServiceInvoice saved = inv.getArgument(0);
            saved.setId(com.vetclinic.common.util.UuidV7.generate());
            return saved;
        });
        lenient().when(vitalSignRepository.existsCompleteMeasurement(any())).thenAnswer(inv -> completeMeasurements.contains(inv.<UUID>getArgument(0)));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // T01 (17/11/2026): tiếp đón lập luôn dịch vụ khám + hóa đơn phí khám
        lenient().when(serviceRepository.findByCode("EXAM_GEN")).thenReturn(Optional.of(EXAM_SVC));
        lenient().when(detailRepository.saveAndFlush(any(VisitServiceDetail.class))).thenAnswer(inv -> {
            VisitServiceDetail saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(lid(500L));
            }
            return saved;
        });
        // Khóa tên giả: chạy thẳng action, giống khi lấy được khóa
        lenient().when(lockService.runLocked(anyCollection(), any())).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(1)).get());
        service = new VisitServiceImpl(visitRepository, petRepository, historyRepository, detailRepository,
                vitalSignRepository, medicalRecordRepository, prescriptionRepository, serviceRepository, authClient, appointmentClient, support, ticketIssuer, assembler,
                lockService, outboxWriter, CLOCK, tm);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ================================================================== tiếp nhận (UC-19)

    @Test
    void checkIn_walkIn_createsWaitingVisitWithEmergencyFlagAndPublishesEvent() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(pet(pid(11), dog())));
        stubSaveVisit();

        VisitResponse response = service.checkIn(new CheckInRequest(pid(11), null, "  Co giật  ", true, null));

        assertThat(response.status()).isEqualTo(VisitStatus.WAITING);
        assertThat(response.visitCode()).matches("VIS-2026-[A-Z2-9]{6}");   // đuôi ngẫu nhiên: khóa là UUID
        assertThat(response.emergency()).isTrue();
        assertThat(response.receptionistId()).isEqualTo(RECEPTIONIST);
        assertThat(response.appointmentId()).isNull();
        assertThat(response.reasonForVisit()).isEqualTo("Co giật");
        assertThat(response.doctorId()).isNull();

        PetVisitStatusHistory history = lastHistory();
        assertThat(history.getFromStatus()).isNull();
        assertThat(history.getToStatus()).isEqualTo(VisitStatus.WAITING);
        assertThat(history.getNotes()).contains("vãng lai").contains("CẤP CỨU");

        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).write(eq("VISIT"), any(), event.capture());
        VisitCheckedInEvent checkedIn = (VisitCheckedInEvent) event.getValue();
        assertThat(checkedIn.visitId()).isEqualTo(lid(100L));
        assertThat(checkedIn.appointmentId()).isNull();
        assertThat(checkedIn.emergency()).isTrue();
    }

    @Test
    void checkIn_withTodaysConfirmedAppointment_usesItsPetAndLinksTheAppointment() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(appointmentClient.getAppointment(apid(7))).thenReturn(ApiResponse.success(appointment("CONFIRMED", LocalDate.of(2026, 10, 30), pid(11))));
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(pet(pid(11), dog())));
        stubSaveVisit();

        // Không gửi petId và triệu chứng: lấy từ lịch hẹn
        VisitResponse response = service.checkIn(new CheckInRequest(null, apid(7), null, false, null));

        assertThat(response.petId()).isEqualTo(pid(11));
        assertThat(response.appointmentId()).isEqualTo(apid(7));
        assertThat(response.reasonForVisit()).isEqualTo("Khám tổng quát");
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).write(eq("VISIT"), any(), event.capture());
        assertThat(((VisitCheckedInEvent) event.getValue()).appointmentId()).isEqualTo(apid(7));
    }

    private PetVisit checkInWithSlot(LocalTime start, LocalTime end) {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(appointmentClient.getAppointment(apid(7))).thenReturn(ApiResponse.success(new AppointmentSummaryResponse(apid(7),
                "APT-20261030-00007", pid(11), OWNER, LocalDate.of(2026, 10, 30), start, end, "Khám", "CONFIRMED")));
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(pet(pid(11), dog())));
        stubSaveVisit();

        service.checkIn(new CheckInRequest(null, apid(7), null, false, null));

        ArgumentCaptor<PetVisit> saved = ArgumentCaptor.forClass(PetVisit.class);
        verify(visitRepository).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    /** Không phát phiếu: cả {@code issue} (ngay) lẫn {@code issueAfterCommit}; VisitAssembler vẫn được phép ĐỌC phiếu khi dựng phản hồi. */
    private void verifyNoTicketIssued() {
        verify(ticketIssuer, never()).issue(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any(), any(), any());
        verify(ticketIssuer, never()).issueAfterCommit(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
    }

    @Test
    void checkIn_createsTheExamFeeAndItsPendingInvoice_butNoTicketUntilTheFeeIsPaid() {
        PetVisit visit = checkInWithSlot(LocalTime.of(9, 0), LocalTime.of(9, 30));

        ArgumentCaptor<VisitServiceDetail> fee = ArgumentCaptor.forClass(VisitServiceDetail.class);
        verify(detailRepository).saveAndFlush(fee.capture());
        assertThat(fee.getValue().getService().getCode()).isEqualTo("EXAM_GEN");
        assertThat(fee.getValue().getStatus()).isEqualTo(ServiceDetailStatus.PENDING);   // IN_PROGRESS ở T02
        ArgumentCaptor<ServiceInvoice> invoice =
                ArgumentCaptor.forClass(ServiceInvoice.class);
        verify(invoiceRepository).saveAndFlush(invoice.capture());
        assertThat(invoice.getValue().getUnitPrice()).isEqualByComparingTo("100000.00");
        assertThat(invoice.getValue().getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        // BR-35: chưa nộp phí khám thì chưa có phiếu, chưa vào hàng đợi Bác sĩ
        verifyNoTicketIssued();   // phiếu không do giao dịch tiếp đón phát: QueueIssuerListener phát khi thu phí / khi nhận VisitCheckedInEvent
        assertThat(lastHistory().getNotes()).contains("lịch hẹn #" + apid(7));
        assertThat(visit.getStatus()).isEqualTo(VisitStatus.WAITING);
    }

    @Test
    void checkIn_emergency_carriesTheEmergencyFlagInTheEvent_soTheListenerIssuesTheTicketImmediately() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(pet(pid(11), dog())));
        stubSaveVisit();

        service.checkIn(new CheckInRequest(pid(11), null, "Co giật", true, null));

        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).write(eq("VISIT"), any(), event.capture());
        assertThat(((VisitCheckedInEvent) event.getValue()).emergency()).isTrue();   // QueueIssuerListener phát phiếu priority 2 từ sự kiện này
        verifyNoTicketIssued();   // không phát phiếu trong giao dịch tiếp đón
    }

    @Test
    void checkIn_whenTheExamServiceNeedsNoPrepayment_stillIssuesNoTicketInTheTransaction_theListenerDecides() {
        // Chính sách thu trước đọc từ nhóm dịch vụ (service_categories.requires_prepayment), không suy ra từ tên nhóm
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        ClinicService freeExam = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00");
        freeExam.getCategory().setRequiresPrepayment(false);
        when(serviceRepository.findByCode("EXAM_GEN")).thenReturn(Optional.of(freeExam));
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(pet(pid(11), dog())));
        stubSaveVisit();

        service.checkIn(new CheckInRequest(pid(11), null, "Khám", false, null));

        verifyNoTicketIssued();   // listener đọc lại danh mục (phí khám không thu trước) và phát phiếu từ VisitCheckedInEvent
    }

    @Test
    void checkIn_appointmentThatIsNotConfirmedOrNotToday_isRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(appointmentClient.getAppointment(apid(1))).thenReturn(ApiResponse.success(appointment("PENDING", LocalDate.of(2026, 10, 30), pid(11))));
        when(appointmentClient.getAppointment(apid(2))).thenReturn(ApiResponse.success(appointment("CONFIRMED", LocalDate.of(2026, 10, 31), pid(11))));
        when(appointmentClient.getAppointment(apid(3))).thenReturn(ApiResponse.success(appointment("NO_SHOW", LocalDate.of(2026, 10, 30), pid(11))));

        for (long id : new long[]{1L, 2L, 3L}) {
            assertError(ErrorCode.APPOINTMENT_NOT_CHECKABLE, () -> service.checkIn(new CheckInRequest(null, apid(id), null, false, null)));
        }
        verify(visitRepository, never()).saveAndFlush(any());
    }

    @Test
    void checkIn_petThatDoesNotMatchTheAppointment_isRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(appointmentClient.getAppointment(apid(7))).thenReturn(ApiResponse.success(appointment("CONFIRMED", LocalDate.of(2026, 10, 30), pid(11))));

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.checkIn(new CheckInRequest(pid(99), apid(7), null, false, null)));
    }

    @Test
    void checkIn_petAlreadyInAnActiveVisit_isRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(pet(pid(11), dog())));
        when(visitRepository.existsByPetIdAndStatusIn(pid(11), VisitStatus.ACTIVE)).thenReturn(true);

        assertError(ErrorCode.PET_HAS_OPEN_VISIT, () -> service.checkIn(new CheckInRequest(pid(11), null, null, false, null)));
        verify(visitRepository, never()).saveAndFlush(any());
        verify(outboxWriter, never()).write(any(), any(), any(DomainEvent.class));
    }

    @Test
    void checkIn_appointmentThatAlreadyHasAVisit_isRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(visitRepository.existsByAppointmentId(apid(7))).thenReturn(true);

        // Chặn ngay trước khi gọi sang appointment-service (lịch đã CHECKED_IN sẽ báo "chưa sẵn sàng" gây khó hiểu)
        assertError(ErrorCode.PET_HAS_OPEN_VISIT, () -> service.checkIn(new CheckInRequest(null, apid(7), null, false, null)));
        verifyNoInteractions(appointmentClient);
    }

    @Test
    void checkIn_withNeitherPetNorAppointment_isRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.checkIn(new CheckInRequest(null, null, null, false, null)));
    }

    // ================================================================== phân công Bác sĩ (UC-21, EX-05)

    @Test
    void assignDoctor_toAvailableDoctor_setsDoctorButKeepsWaiting() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(DOCTOR, RoleCode.VETERINARIAN, "ACTIVE", "P01");
        PetVisit visit = visit(100L, VisitStatus.WAITING, null);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));

        VisitResponse response = service.assignDoctor(lid(100L), new AssignDoctorRequest(DOCTOR));

        assertThat(response.doctorId()).isEqualTo(DOCTOR);
        // Số phòng không còn cột assigned_room: lấy từ Bác sĩ (users.room_number) vừa được kiểm tra qua auth-service
        assertThat(response.assignedRoom()).isEqualTo("P01");
        assertThat(visit.getDoctorId()).isEqualTo(DOCTOR);
        assertThat(visit.getDoctorNameSnapshot()).isEqualTo("BS " + DOCTOR);   // chụp tên Bác sĩ lúc phân công (tránh N+1)
        assertThat(response.doctorName()).isEqualTo("BS " + DOCTOR);
        assertThat(response.status()).isEqualTo(VisitStatus.WAITING);
        // Trạng thái không đổi nên KHÔNG ghi pet_visit_status_histories (State Machine Q2)
        verify(historyRepository, never()).save(any());
    }

    @Test
    void assignDoctor_ignoresWhetherTheDoctorIsBusy_FR24() {
        // v3.0: Lễ tân xếp ca vào hàng đợi của Bác sĩ BẤT KỲ, bất cứ lúc nào, không cần Bác sĩ rảnh (Bác sĩ đang khám ca khác vẫn nhận)
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(DOCTOR, RoleCode.VETERINARIAN, "ACTIVE", "P01");
        PetVisit visit = visit(100L, VisitStatus.WAITING, null);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));

        service.assignDoctor(lid(100L), new AssignDoctorRequest(DOCTOR));

        assertThat(visit.getDoctorId()).isEqualTo(DOCTOR);
    }

    @Test
    void assignDoctor_toAnotherDoctor_movesTheVisitAndItsWaitingTicket() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(OTHER_DOCTOR, RoleCode.VETERINARIAN, "ACTIVE", "P02");
        PetVisit visit = visit(100L, VisitStatus.WAITING, DOCTOR);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));

        service.assignDoctor(lid(100L), new AssignDoctorRequest(OTHER_DOCTOR));

        assertThat(visit.getDoctorId()).isEqualTo(OTHER_DOCTOR);
        verify(ticketIssuer).reassignDoctorTicket(lid(100L), OTHER_DOCTOR);   // phiếu giữ nguyên số và giờ phát
    }

    @Test
    void unassignDoctor_sendsTheVisitBackToTheUnassignedList_withItsTicket() {
        PetVisit visit = visit(100L, VisitStatus.WAITING, DOCTOR);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));

        service.unassignDoctor(lid(100L));

        assertThat(visit.getDoctorId()).isNull();
        verify(ticketIssuer).reassignDoctorTicket(lid(100L), null);
        assertThat(visit.getStatus()).isEqualTo(VisitStatus.WAITING);
        verify(historyRepository, never()).save(any());
    }

    @Test
    void unassignDoctor_refusedWhenNotWaiting_orWhenNobodyIsAssigned() {
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, DOCTOR)));
        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(visit(101L, VisitStatus.WAITING, null)));

        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.unassignDoctor(lid(100L)));   // bác sĩ đã bắt đầu khám: FR-25
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.unassignDoctor(lid(101L)));
    }

    @Test
    void checkIn_withADoctor_putsTheVisitInThatDoctorsQueueAndTellsTheEvent() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(DOCTOR, RoleCode.VETERINARIAN, "ACTIVE", "P01");
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(pet(pid(11), dog())));
        stubSaveVisit();

        VisitResponse response = service.checkIn(new CheckInRequest(pid(11), null, null, false, DOCTOR));

        assertThat(response.doctorId()).isEqualTo(DOCTOR);
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).write(eq("VISIT"), any(), event.capture());
        assertThat(((VisitCheckedInEvent) event.getValue()).doctorId()).isEqualTo(DOCTOR);
        assertThat(lastHistory().getNotes()).contains("chọn Bác sĩ #" + DOCTOR);   // vào hàng đợi Bác sĩ này khi đã nộp phí khám
    }

    @Test
    void checkIn_withAnInvalidDoctor_isRefusedBeforeAnythingIsSaved() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(uid(30), RoleCode.LAB_TECHNICIAN, "ACTIVE", null);

        assertError(ErrorCode.DOCTOR_INACTIVE, () -> service.checkIn(new CheckInRequest(pid(11), null, null, false, uid(30))));
        verify(visitRepository, never()).saveAndFlush(any());
    }

    @Test
    void assignDoctor_requiresAnActiveVeterinarian() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(uid(30), RoleCode.LAB_TECHNICIAN, "ACTIVE", null);
        stubDoctor(uid(31), RoleCode.VETERINARIAN, "LOCKED", "P03");

        assertError(ErrorCode.DOCTOR_INACTIVE, () -> service.assignDoctor(lid(100L), new AssignDoctorRequest(uid(30))));
        assertError(ErrorCode.DOCTOR_INACTIVE, () -> service.assignDoctor(lid(100L), new AssignDoctorRequest(uid(31))));
        verify(lockService, never()).runLocked(anyCollection(), any());
    }

    @Test
    void assignDoctor_onlyForWaitingVisits_andNotTheSameDoctorTwice() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubDoctor(DOCTOR, RoleCode.VETERINARIAN, "ACTIVE", "P01");
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, OTHER_DOCTOR)));
        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(visit(101L, VisitStatus.WAITING, DOCTOR)));

        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.assignDoctor(lid(100L), new AssignDoctorRequest(DOCTOR)));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.assignDoctor(lid(101L), new AssignDoctorRequest(DOCTOR)));
    }

    // ================================================================== khám giai đoạn 1 (UC-22, UC-23)

    @Test
    void startExam_byAssignedDoctor_takesTheTicket_createsTheMedicalRecord_recordsVitalSigns_andStartsTheExamFee() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visit(100L, VisitStatus.WAITING, DOCTOR);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        VisitServiceDetail fee = detail(500L, 100L, EXAM_SVC, ServiceDetailStatus.PENDING);
        when(detailRepository.findByVisitIdOrderByIdAsc(lid(100L))).thenReturn(List.of(fee));
        when(vitalSignRepository.save(any(VitalSign.class))).thenAnswer(inv -> inv.getArgument(0));
        VitalSignsRequest vitals = new VitalSignsRequest(new BigDecimal("12.5"), new BigDecimal("38.6"), null, null, null, null, null, null);

        VisitResponse response = service.startExam(lid(100L), new StartExamRequest(vitals, "  Ăn kém  "));

        assertThat(response.status()).isEqualTo(VisitStatus.IN_PROGRESS);
        verify(ticketIssuer).startDoctorTicket(lid(100L), DOCTOR);   // phiếu DOCTOR WAITING -> IN_SERVICE
        // T02: tạo medical_records (1-1 với đợt khám) kèm bệnh sử
        ArgumentCaptor<MedicalRecord> record = ArgumentCaptor.forClass(MedicalRecord.class);
        verify(medicalRecordRepository).save(record.capture());
        assertThat(record.getValue().getVisitId()).isEqualTo(lid(100L));
        assertThat(record.getValue().getAnamnesis()).isEqualTo("Ăn kém");
        // Lần đo đầu tiên là MỘT dòng vital_signs, người đo là Bác sĩ
        ArgumentCaptor<VitalSign> vital = ArgumentCaptor.forClass(VitalSign.class);
        verify(vitalSignRepository).save(vital.capture());
        assertThat(vital.getValue().getVisitId()).isEqualTo(lid(100L));
        assertThat(vital.getValue().getWeightKg()).isEqualByComparingTo("12.5");
        assertThat(vital.getValue().getTemperatureC()).isEqualByComparingTo("38.6");
        assertThat(vital.getValue().getRecordedBy()).isEqualTo(DOCTOR);
        assertThat(fee.getStatus()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);   // T02: hoàn tất ở T04/T05/T09
        verify(detailRepository, never()).saveAndFlush(any());                    // không lập dịch vụ / hóa đơn mới
        verify(invoiceRepository, never()).saveAndFlush(any());
        assertThat(lastHistory().getFromStatus()).isEqualTo(VisitStatus.WAITING);
        assertThat(lastHistory().getToStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
    }

    @Test
    void startExam_withoutATicket_isRefused_CLIN_1314() {
        // Chưa nộp phí khám (không phải ca cấp cứu) thì chưa có phiếu: Bác sĩ không bắt đầu khám được
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visit(100L, VisitStatus.WAITING, DOCTOR);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        when(ticketIssuer.startDoctorTicket(lid(100L), DOCTOR)).thenThrow(new AppException(ErrorCode.NO_QUEUE_TICKET));

        assertError(ErrorCode.NO_QUEUE_TICKET, () -> service.startExam(lid(100L), new StartExamRequest(null, null)));
        assertThat(visit.getStatus()).isEqualTo(VisitStatus.WAITING);
        verify(medicalRecordRepository, never()).save(any());
    }

    @Test
    void startExam_rejectsDoctorWhoWasNotAssigned_orIsAlreadyExamining_orWrongStatus() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        StartExamRequest request = new StartExamRequest(null, null);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING, OTHER_DOCTOR)));
        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(visit(101L, VisitStatus.WAITING, DOCTOR)));
        when(visitRepository.findByIdForUpdate(lid(102L))).thenReturn(Optional.of(visit(102L, VisitStatus.WAITING_RESULTS, DOCTOR)));
        when(visitRepository.existsByDoctorIdAndStatus(DOCTOR, VisitStatus.IN_PROGRESS)).thenReturn(true);

        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.startExam(lid(100L), request));
        assertError(ErrorCode.DOCTOR_NOT_AVAILABLE, () -> service.startExam(lid(101L), request));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.startExam(lid(102L), request));
    }

    /** Ca đã có ít nhất một lần đo đủ cân nặng + thân nhiệt (T04 / chốt ca được phép đi tiếp). */
    private PetVisit visitWithVitals(long id, VisitStatus status) {
        completeMeasurements.add(lid(id));
        return visit(id, status, DOCTOR);
    }

    @Test
    void startExam_vitalSignsAndAnamnesisAreOptional_stillCreatesTheMedicalRecord() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visit(100L, VisitStatus.WAITING, DOCTOR);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));

        service.startExam(lid(100L), new StartExamRequest(null, null));

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        verify(vitalSignRepository, never()).save(any());   // sinh hiệu có thể ghi sau bằng POST /vital-signs
        verify(medicalRecordRepository).save(any(MedicalRecord.class));
    }

    @Test
    void recordVitalSigns_addsANewRowEachTime_neverOverwrites() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visit(100L, VisitStatus.IN_PROGRESS, DOCTOR);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        when(vitalSignRepository.save(any(VitalSign.class))).thenAnswer(inv -> inv.getArgument(0));

        VitalSignResponse first = service.recordVitalSigns(lid(100L),
                new VitalSignsRequest(new BigDecimal("4.60"), new BigDecimal("39.2"), 160, 40, 5, "Hồng", new BigDecimal("1.5"), "Đo đầu ca"));
        VitalSignResponse second = service.recordVitalSigns(lid(100L),
                new VitalSignsRequest(null, new BigDecimal("38.4"), null, null, null, null, null, "Đo lại sau hạ sốt"));

        verify(vitalSignRepository, Mockito.times(2)).save(any(VitalSign.class));   // hai dòng riêng, không sửa dòng cũ
        assertThat(first.weightKg()).isEqualByComparingTo("4.60");
        assertThat(first.heartRateBpm()).isEqualTo(160);
        assertThat(first.recordedBy()).isEqualTo(DOCTOR);
        assertThat(second.weightKg()).isNull();
        assertThat(second.temperatureC()).isEqualByComparingTo("38.4");
    }

    @Test
    void recordVitalSigns_refused_whenEmpty_notInProgress_orNotTheAssignedDoctor() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR)));   // đã khóa
        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(visit(101L, VisitStatus.IN_PROGRESS, OTHER_DOCTOR)));
        VitalSignsRequest some = new VitalSignsRequest(new BigDecimal("4.6"), null, null, null, null, null, null, null);

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.recordVitalSigns(lid(100L), new VitalSignsRequest(null, null, null, null, null, " ", null, null)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.recordVitalSigns(lid(100L), some));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.recordVitalSigns(lid(101L), some));
        verify(vitalSignRepository, never()).save(any());
    }

    @Test
    void updateMedicalRecord_changesOnlyTheGivenFields_whileExamining() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visit(100L, VisitStatus.IN_PROGRESS, DOCTOR);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        MedicalRecord record = MedicalRecord.builder().visitId(lid(100L)).anamnesis("Bỏ ăn 2 ngày").clinicalFindings("Mất nước nhẹ").version(3L).build();
        when(medicalRecordRepository.findById(lid(100L))).thenReturn(Optional.of(record));
        when(medicalRecordRepository.saveAndFlush(record)).thenReturn(record);

        MedicalRecordResponse response = service.updateMedicalRecord(lid(100L), new UpdateMedicalRecordRequest(
                null, null, "Viêm dạ dày ruột nghi do ăn nhầm", null, null, null, LocalDate.of(2026, 11, 6), 3L));

        assertThat(record.getAnamnesis()).isEqualTo("Bỏ ăn 2 ngày");            // giữ nguyên
        assertThat(record.getClinicalFindings()).isEqualTo("Mất nước nhẹ");     // giữ nguyên
        assertThat(record.getPreliminaryDiagnosis()).isEqualTo("Viêm dạ dày ruột nghi do ăn nhầm");
        assertThat(record.getFollowUpDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(response.locked()).isFalse();
    }

    @Test
    void updateMedicalRecord_withAStaleVersion_isRefusedInsteadOfOverwriting() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, DOCTOR)));
        when(medicalRecordRepository.findById(lid(100L))).thenReturn(Optional.of(MedicalRecord.builder().visitId(lid(100L)).version(5L).build()));

        assertError(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, () -> service.updateMedicalRecord(lid(100L),
                new UpdateMedicalRecordRequest("Sửa trên bản cũ", null, null, null, null, null, null, 4L)));
        verify(medicalRecordRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateMedicalRecord_isLockedFromClosingOnward_CLIN_1317_andOnlyForTheAssignedDoctor() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR)));
        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(visit(101L, VisitStatus.COMPLETED, DOCTOR)));
        when(visitRepository.findByIdForUpdate(lid(102L))).thenReturn(Optional.of(visit(102L, VisitStatus.IN_PROGRESS, OTHER_DOCTOR)));
        var request = new UpdateMedicalRecordRequest("x", null, null, null, null, null, null, null);

        assertError(ErrorCode.MEDICAL_RECORD_LOCKED, () -> service.updateMedicalRecord(lid(100L), request));
        assertError(ErrorCode.MEDICAL_RECORD_LOCKED, () -> service.updateMedicalRecord(lid(101L), request));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.updateMedicalRecord(lid(102L), request));
        verify(medicalRecordRepository, never()).saveAndFlush(any());
    }

    // ================================================================== chỉ định cận lâm sàng (T04)

    @Test
    void orderParaclinical_createsPendingLinesAndInvoices_completesTheExam_andReleasesTheDoctor() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visitWithVitals(100L, VisitStatus.IN_PROGRESS);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        ClinicService cbc = service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00");
        ClinicService xray = service(7L, "IMG_XRAY", ServiceCategory.IMAGING, "180000.00");
        when(serviceRepository.findByIdInAndActiveTrue(List.of(com.vetclinic.clinical.testsupport.TestData.sid(5), com.vetclinic.clinical.testsupport.TestData.sid(7)))).thenReturn(List.of(cbc, xray));
        VisitServiceDetail examLine = detail(1L, 100L, service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00"),
                ServiceDetailStatus.IN_PROGRESS);
        when(detailRepository.findByVisitIdOrderByIdAsc(lid(100L))).thenReturn(List.of(examLine));
        stubSaveDetail();

        VisitResponse response = service.orderParaclinical(lid(100L),
                new ParaclinicalOrderRequest(List.of(com.vetclinic.clinical.testsupport.TestData.sid(5), com.vetclinic.clinical.testsupport.TestData.sid(7)), "Nghi viêm tụy"));

        assertThat(response.status()).isEqualTo(VisitStatus.WAITING_RESULTS);
        assertThat(examLine.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);   // dịch vụ khám của GĐ 1 hoàn tất
        ArgumentCaptor<VisitServiceDetail> lines = ArgumentCaptor.forClass(VisitServiceDetail.class);
        verify(detailRepository, Mockito.times(2)).saveAndFlush(lines.capture());
        assertThat(lines.getAllValues()).extracting(VisitServiceDetail::getStatus)
                .containsOnly(ServiceDetailStatus.PENDING);   // tự vào hàng đợi Lab / CĐHA
        assertThat(lines.getAllValues()).extracting(VisitServiceDetail::getNotes).containsOnly("Nghi viêm tụy");
        ArgumentCaptor<ServiceInvoice> invoices =
                ArgumentCaptor.forClass(ServiceInvoice.class);
        verify(invoiceRepository, Mockito.times(2)).saveAndFlush(invoices.capture());
        assertThat(invoices.getAllValues()).extracting(i -> i.getUnitPrice().toPlainString()).containsExactly("250000.00", "180000.00");
        assertThat(lastHistory().getToStatus()).isEqualTo(VisitStatus.WAITING_RESULTS);
    }

    @Test
    void orderParaclinical_requiresWeightAndTemperature_CLIN_1308() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        ClinicService cbc = service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00");
        when(serviceRepository.findByIdInAndActiveTrue(List.of(com.vetclinic.clinical.testsupport.TestData.sid(5)))).thenReturn(List.of(cbc));
        var request = new ParaclinicalOrderRequest(List.of(com.vetclinic.clinical.testsupport.TestData.sid(5)), null);

        PetVisit noVitals = visit(100L, VisitStatus.IN_PROGRESS, DOCTOR);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(noVitals));
        assertError(ErrorCode.VITAL_SIGNS_REQUIRED, () -> service.orderParaclinical(lid(100L), request));

        // Chỉ có lần đo thiếu một chỉ số (existsCompleteMeasurement = false): vẫn bị chặn
        PetVisit incomplete = visit(101L, VisitStatus.IN_PROGRESS, DOCTOR);
        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(incomplete));
        assertError(ErrorCode.VITAL_SIGNS_REQUIRED, () -> service.orderParaclinical(lid(101L), request));
        verify(detailRepository, never()).saveAndFlush(any());
    }

    @Test
    void orderParaclinical_refusedWhileAnInRoomServiceIsStillOpen_CLIN_1309() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(serviceRepository.findByIdInAndActiveTrue(List.of(com.vetclinic.clinical.testsupport.TestData.sid(5)))).thenReturn(List.of(service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00")));
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visitWithVitals(100L, VisitStatus.IN_PROGRESS)));
        when(detailRepository.countByVisitAndKindAndStatuses(lid(100L), ServiceKind.IN_ROOM, ServiceDetailStatus.OPEN)).thenReturn(1L);

        assertError(ErrorCode.IN_ROOM_SERVICE_PENDING,
                () -> service.orderParaclinical(lid(100L), new ParaclinicalOrderRequest(List.of(com.vetclinic.clinical.testsupport.TestData.sid(5)), null)));
        verify(detailRepository, never()).saveAndFlush(any());
    }

    @Test
    void orderParaclinical_rejectsNonParaclinicalUnknownLockedAndPhaseTwo() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        // Dịch vụ tại phòng không đi qua cận lâm sàng
        when(serviceRepository.findByIdInAndActiveTrue(List.of(com.vetclinic.clinical.testsupport.TestData.sid(2)))).thenReturn(List.of(service(2L, "VAC_7B", "VACCINATION", "150000.00")));
        assertError(ErrorCode.INVALID_INPUT_DATA,
                () -> service.orderParaclinical(lid(100L), new ParaclinicalOrderRequest(List.of(com.vetclinic.clinical.testsupport.TestData.sid(2)), null)));
        // Dịch vụ không tồn tại / đã ngừng
        when(serviceRepository.findByIdInAndActiveTrue(List.of(com.vetclinic.clinical.testsupport.TestData.sid(99)))).thenReturn(List.of());
        assertError(ErrorCode.SERVICE_NOT_FOUND,
                () -> service.orderParaclinical(lid(100L), new ParaclinicalOrderRequest(List.of(com.vetclinic.clinical.testsupport.TestData.sid(99)), null)));

        ClinicService cbc = service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00");
        when(serviceRepository.findByIdInAndActiveTrue(List.of(com.vetclinic.clinical.testsupport.TestData.sid(5)))).thenReturn(List.of(cbc));
        var request = new ParaclinicalOrderRequest(List.of(com.vetclinic.clinical.testsupport.TestData.sid(5)), null);
        // Hồ sơ đã khóa
        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(visitWithVitals(101L, VisitStatus.WAITING_PAYMENT)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.orderParaclinical(lid(101L), request));
        // Giai đoạn 2: đã từng qua WAITING_RESULTS
        when(visitRepository.findByIdForUpdate(lid(102L))).thenReturn(Optional.of(visitWithVitals(102L, VisitStatus.IN_PROGRESS)));
        when(historyRepository.existsByVisitIdAndToStatus(lid(102L), VisitStatus.WAITING_RESULTS)).thenReturn(true);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.orderParaclinical(lid(102L), request));
        // Bác sĩ khác
        when(visitRepository.findByIdForUpdate(lid(103L))).thenReturn(Optional.of(visit(103L, VisitStatus.IN_PROGRESS, OTHER_DOCTOR)));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.orderParaclinical(lid(103L), request));
        verify(detailRepository, never()).saveAndFlush(any());
    }

    // ================================================================== dịch vụ tại phòng (FR-30)

    @Test
    void orderInRoomServices_createsPendingLinesWithInvoices_andKeepsTheVisitInProgress() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visitWithVitals(100L, VisitStatus.IN_PROGRESS);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        ClinicService vaccine = service(2L, "VAC_7B", "VACCINATION", "150000.00");
        when(serviceRepository.findByIdInAndActiveTrue(List.of(com.vetclinic.clinical.testsupport.TestData.sid(2)))).thenReturn(List.of(vaccine));
        stubSaveDetail();

        VisitResponse response = service.orderInRoomServices(lid(100L), new OrderServicesRequest(List.of(new ServiceOrderItem(com.vetclinic.clinical.testsupport.TestData.sid(2), 2, "Mũi 1"))));

        assertThat(response.status()).isEqualTo(VisitStatus.IN_PROGRESS);
        ArgumentCaptor<VisitServiceDetail> line = ArgumentCaptor.forClass(VisitServiceDetail.class);
        verify(detailRepository).saveAndFlush(line.capture());
        assertThat(line.getValue().getStatus()).isEqualTo(ServiceDetailStatus.PENDING);   // chờ "Đã thực hiện"
        assertThat(line.getValue().getQuantity()).isEqualTo(2);
        ArgumentCaptor<ServiceInvoice> invoice =
                ArgumentCaptor.forClass(ServiceInvoice.class);
        verify(invoiceRepository).saveAndFlush(invoice.capture());
        assertThat(invoice.getValue().getUnitPrice()).isEqualByComparingTo("150000.00");
        verify(historyRepository, never()).save(any());   // không đổi trạng thái ca
    }

    @Test
    void orderInRoomServices_rejectsTheExamFeeAndParaclinicalServices_andUnknownOnes() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(serviceRepository.findByIdInAndActiveTrue(List.of(com.vetclinic.clinical.testsupport.TestData.sid(1)))).thenReturn(List.of(service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00")));
        when(serviceRepository.findByIdInAndActiveTrue(List.of(com.vetclinic.clinical.testsupport.TestData.sid(5)))).thenReturn(List.of(service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00")));
        when(serviceRepository.findByIdInAndActiveTrue(List.of(com.vetclinic.clinical.testsupport.TestData.sid(99)))).thenReturn(List.of());

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.orderInRoomServices(lid(100L), new OrderServicesRequest(List.of(new ServiceOrderItem(com.vetclinic.clinical.testsupport.TestData.sid(1), 1, null)))));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.orderInRoomServices(lid(100L), new OrderServicesRequest(List.of(new ServiceOrderItem(com.vetclinic.clinical.testsupport.TestData.sid(5), 1, null)))));
        assertError(ErrorCode.SERVICE_NOT_FOUND, () -> service.orderInRoomServices(lid(100L), new OrderServicesRequest(List.of(new ServiceOrderItem(com.vetclinic.clinical.testsupport.TestData.sid(99), 1, null)))));
        verify(detailRepository, never()).saveAndFlush(any());
    }

    @Test
    void orderInRoomServices_refusedOnALockedVisit() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(serviceRepository.findByIdInAndActiveTrue(List.of(com.vetclinic.clinical.testsupport.TestData.sid(4)))).thenReturn(List.of(service(4L, "DEWORM", "DEWORMING", "50000.00")));
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR)));

        assertError(ErrorCode.INVALID_STATUS_TRANSITION,
                () -> service.orderInRoomServices(lid(100L), new OrderServicesRequest(List.of(new ServiceOrderItem(com.vetclinic.clinical.testsupport.TestData.sid(4), 1, null)))));   // BR-27: hồ sơ đã khóa
    }

    // ================================================================== bắt đầu Giai đoạn 2 (T07)

    @Test
    void startPhase2_movesResultsReadyToInProgress_forTheAssignedDoctor() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visitWithVitals(100L, VisitStatus.RESULTS_READY);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));

        VisitResponse response = service.startPhase2(lid(100L));

        assertThat(response.status()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(lastHistory().getFromStatus()).isEqualTo(VisitStatus.RESULTS_READY);
        assertThat(lastHistory().getToStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(lastHistory().getChangedByUserId()).isEqualTo(DOCTOR);
    }

    @Test
    void startPhase2_refusedForOtherDoctorWrongStateOrABusyDoctor() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.startPhase2(lid(100L)));   // chưa có đủ kết quả

        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(visit(101L, VisitStatus.RESULTS_READY, OTHER_DOCTOR)));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.startPhase2(lid(101L)));

        when(visitRepository.findByIdForUpdate(lid(102L))).thenReturn(Optional.of(visit(102L, VisitStatus.RESULTS_READY, DOCTOR)));
        when(visitRepository.existsByDoctorIdAndStatus(DOCTOR, VisitStatus.IN_PROGRESS)).thenReturn(true);
        assertError(ErrorCode.DOCTOR_NOT_AVAILABLE, () -> service.startPhase2(lid(102L)));
    }

    // ================================================================== chốt ca (T05 / T09, UC-33)

    @Test
    void closeVisit_withoutAnyDiagnosis_isRefused_CLIN_1307_andNothingChanges() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visitWithVitals(100L, VisitStatus.IN_PROGRESS);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        when(medicalRecordRepository.findById(lid(100L))).thenReturn(Optional.of(MedicalRecord.builder().visitId(lid(100L)).build()));
        for (String blank : new String[]{null, "", "   "}) {
            assertError(ErrorCode.DIAGNOSIS_REQUIRED, () -> service.closeVisit(lid(100L), new CloseVisitRequest(blank, null, null, null)));
        }
        assertThat(visit.getStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        verify(medicalRecordRepository, never()).save(any());
        verify(outboxWriter, never()).write(any(), any(), any(DomainEvent.class));
    }

    @Test
    void closeVisit_withoutParaclinical_fallsBackToThePreliminaryDiagnosisOnTheMedicalRecord() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visitWithVitals(100L, VisitStatus.IN_PROGRESS);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        MedicalRecord record = MedicalRecord.builder().visitId(lid(100L)).preliminaryDiagnosis("Viêm da dị ứng").build();
        when(medicalRecordRepository.findById(lid(100L))).thenReturn(Optional.of(record));

        service.closeVisit(lid(100L), new CloseVisitRequest(null, "Thuốc bôi 7 ngày", null, null));

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.WAITING_PAYMENT);
        assertThat(record.getFinalDiagnosis()).isEqualTo("Viêm da dị ứng");   // ca không làm CLS: chẩn đoán sơ bộ thành chẩn đoán chốt (FR-54)
        assertThat(record.getTreatmentPlan()).isEqualTo("Thuốc bôi 7 ngày");
    }

    @Test
    void closeVisit_afterParaclinical_needsTheFinalDiagnosis_theTentativeOneIsNotEnough() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visitWithVitals(100L, VisitStatus.IN_PROGRESS);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        when(historyRepository.existsByVisitIdAndToStatus(lid(100L), VisitStatus.WAITING_RESULTS)).thenReturn(true);   // Giai đoạn 2
        when(medicalRecordRepository.findById(lid(100L))).thenReturn(Optional.of(
                MedicalRecord.builder().visitId(lid(100L)).preliminaryDiagnosis("Nghi viêm tụy").build()));

        assertError(ErrorCode.DIAGNOSIS_REQUIRED, () -> service.closeVisit(lid(100L), new CloseVisitRequest(null, null, null, null)));
    }

    @Test
    void closeVisit_writesTheMedicalRecord_locksIt_completesTheExamFee_andPublishesNothing() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visitWithVitals(100L, VisitStatus.IN_PROGRESS);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        MedicalRecord record = MedicalRecord.builder().visitId(lid(100L)).anamnesis("Nôn 3 lần").build();
        when(medicalRecordRepository.findById(lid(100L))).thenReturn(Optional.of(record));
        VisitServiceDetail exam = detail(1L, 100L, service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00"),
                ServiceDetailStatus.IN_PROGRESS);
        when(detailRepository.findByVisitIdOrderByIdAsc(lid(100L))).thenReturn(List.of(exam));

        VisitResponse response = service.closeVisit(lid(100L),
                new CloseVisitRequest("Viêm dạ dày ruột", "Truyền dịch 3 ngày", "Ăn cháo loãng", LocalDate.of(2026, 11, 6)));

        assertThat(response.status()).isEqualTo(VisitStatus.WAITING_PAYMENT);
        assertThat(response.locked()).isTrue();
        assertThat(record.getFinalDiagnosis()).isEqualTo("Viêm dạ dày ruột");
        assertThat(record.getTreatmentPlan()).isEqualTo("Truyền dịch 3 ngày");
        assertThat(record.getDoctorAdvice()).isEqualTo("Ăn cháo loãng");
        assertThat(record.getFollowUpDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(response.medicalRecord().finalDiagnosis()).isEqualTo("Viêm dạ dày ruột");   // hồ sơ khám trả kèm, đã khóa
        assertThat(response.medicalRecord().locked()).isTrue();
        assertThat(exam.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);   // hóa đơn của dịch vụ khám giữ PENDING cho thu ngân
        verify(outboxWriter, never()).write(any(), any(), any(DomainEvent.class));   // v3.0: không còn event "đóng ca" sang billing
    }

    @Test
    void closeVisit_withPrescription_writesPrescriptionCreatedEventToTheOutboxInTheSameTransaction() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visitWithVitals(100L, VisitStatus.IN_PROGRESS);
        visit.setDoctorNameSnapshot("BS Tiến");
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        when(medicalRecordRepository.findById(lid(100L))).thenReturn(Optional.of(
                MedicalRecord.builder().visitId(lid(100L)).preliminaryDiagnosis("Viêm da").build()));
        when(detailRepository.findByVisitIdOrderByIdAsc(lid(100L))).thenReturn(List.of());
        com.vetclinic.clinical.entity.Prescription prescription = com.vetclinic.clinical.entity.Prescription.builder().id(uid(77)).visitId(lid(100L)).build();
        prescription.addItem(com.vetclinic.clinical.entity.PrescriptionItem.builder().medicineId(uid(31)).quantity(10).build());
        prescription.addItem(com.vetclinic.clinical.entity.PrescriptionItem.builder().medicineId(uid(32)).quantity(2).build());
        when(prescriptionRepository.findWithItemsByVisitId(lid(100L))).thenReturn(Optional.of(prescription));

        service.closeVisit(lid(100L), new CloseVisitRequest(null, null, null, null));

        org.mockito.ArgumentCaptor<DomainEvent> event = org.mockito.ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).write(eq("VISIT"), eq(lid(100L)), event.capture());
        com.vetclinic.common.event.PrescriptionCreatedEvent created = (com.vetclinic.common.event.PrescriptionCreatedEvent) event.getValue();
        assertThat(created.routingKey()).isEqualTo("prescription.created");
        assertThat(created.prescriptionId()).isEqualTo(uid(77));
        assertThat(created.visitId()).isEqualTo(lid(100L));
        assertThat(created.actorUserId()).isEqualTo(DOCTOR);
        assertThat(created.customerName()).isEqualTo("Hoàng Thị Lan");   // chụp từ hồ sơ thú cưng
        assertThat(created.customerPhone()).isEqualTo("0912000008");
        assertThat(created.doctorName()).isEqualTo("BS Tiến");
        assertThat(created.items()).extracting(com.vetclinic.common.event.PrescriptionCreatedEvent.Item::medicineId, com.vetclinic.common.event.PrescriptionCreatedEvent.Item::quantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(uid(31), 10), org.assertj.core.groups.Tuple.tuple(uid(32), 2));
    }

    @Test
    void closeVisit_isRefusedWhileParaclinicalServicesAreUnfinished_orByAnotherDoctor() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        CloseVisitRequest request = new CloseVisitRequest("Chẩn đoán", null, null, null);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, DOCTOR)));
        when(detailRepository.countByVisitAndKindAndStatuses(lid(100L), ServiceKind.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);
        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(visit(101L, VisitStatus.IN_PROGRESS, OTHER_DOCTOR)));

        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.closeVisit(lid(100L), request));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.closeVisit(lid(101L), request));
    }

    @Test
    void closeVisit_requiresVitalSigns_CLIN_1308_andNoOpenInRoomService_CLIN_1309() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        CloseVisitRequest request = new CloseVisitRequest("Chẩn đoán", null, null, null);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, DOCTOR)));
        assertError(ErrorCode.VITAL_SIGNS_REQUIRED, () -> service.closeVisit(lid(100L), request));

        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(visitWithVitals(101L, VisitStatus.IN_PROGRESS)));
        lenient().when(detailRepository.countByVisitAndKindAndStatuses(lid(101L), ServiceKind.IN_ROOM, ServiceDetailStatus.OPEN)).thenReturn(1L);
        assertError(ErrorCode.IN_ROOM_SERVICE_PENDING, () -> service.closeVisit(lid(101L), request));
        verify(historyRepository, never()).save(any());
    }

    // ================================================================== khách bỏ về (EX-11: T08, T12)

    @Test
    void abandon_fromResultsReady_closesTheVisitAsCompleted_T08_andNotifiesWithoutCreatingInvoices() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        PetVisit visit = visit(100L, VisitStatus.RESULTS_READY, DOCTOR);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));

        VisitResponse response = service.abandon(lid(100L));

        assertThat(response.status()).isEqualTo(VisitStatus.COMPLETED);
        assertThat(lastHistory().getChangedByUserId()).isEqualTo(RECEPTIONIST);
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).write(eq("VISIT"), any(), event.capture());
        VisitCompletedEvent completed = (VisitCompletedEvent) event.getValue();
        assertThat(completed.abandoned()).isTrue();
        assertThat(completed.visitId()).isEqualTo(lid(100L));
        verify(invoiceRepository, never()).saveAndFlush(any());
    }

    @Test
    void abandon_fromWaitingResults_T12_cancelsPendingParaclinicalWithInvoice_butKeepsTheDoneOnesAsDebt() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        PetVisit visit = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        VisitServiceDetail exam = detail(1L, 100L, service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00"), ServiceDetailStatus.COMPLETED);
        VisitServiceDetail done = detail(2L, 100L, service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00"), ServiceDetailStatus.COMPLETED);
        VisitServiceDetail pending = detail(3L, 100L, service(6L, "IMG_US_AB", ServiceCategory.IMAGING, "200000.00"), ServiceDetailStatus.PENDING);
        when(detailRepository.findByVisitIdOrderByIdAsc(lid(100L))).thenReturn(List.of(exam, done, pending));
        ServiceInvoice invoice = ServiceInvoice.builder()
                .id(lid(50L)).visitServiceDetailId(lid(3L)).unitPrice(new BigDecimal("200000.00")).vatRate(BigDecimal.ZERO).build();
        when(invoiceRepository.findByVisitServiceDetailId(lid(3L))).thenReturn(Optional.of(invoice));

        VisitResponse response = service.abandon(lid(100L));

        assertThat(response.status()).isEqualTo(VisitStatus.COMPLETED);
        assertThat(pending.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(invoice.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(done.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);   // đã làm: giữ hóa đơn PENDING làm công nợ
        assertThat(exam.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);
        verify(invoiceRepository, never()).findByVisitServiceDetailId(lid(2L));
        verify(invoiceRepository, never()).findByVisitServiceDetailId(lid(1L));
    }

    @Test
    void abandon_refusedWhileAParaclinicalServiceIsInProgress_CLIN_1304() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR)));
        when(detailRepository.countByVisitAndKindAndStatuses(lid(100L), ServiceKind.PARACLINICAL,
                List.of(ServiceDetailStatus.IN_PROGRESS))).thenReturn(1L);

        assertError(ErrorCode.PARACLINICAL_SERVICE_IN_PROGRESS, () -> service.abandon(lid(100L)));
        verify(historyRepository, never()).save(any());
    }

    @Test
    void abandon_onlyAfterResultsAreAwaitedOrReady_andOtherDoctorOrOtherRolesCannotDoIt() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.abandon(lid(100L)));

        loginAs(OTHER_DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(visit(101L, VisitStatus.RESULTS_READY, DOCTOR)));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.abandon(lid(101L)));

        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(visitRepository.findByIdForUpdate(lid(102L))).thenReturn(Optional.of(visit(102L, VisitStatus.RESULTS_READY, DOCTOR)));
        assertError(ErrorCode.ACCESS_DENIED, () -> service.abandon(lid(102L)));
    }

    // ================================================================== hủy ca / chuyển viện (T11) và hủy tiếp nhận (T03)

    @Test
    void cancelExam_cancelsEveryOpenServiceWithItsInvoice_andTheVisit() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visit(100L, VisitStatus.IN_PROGRESS, DOCTOR);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        VisitServiceDetail exam = detail(1L, 100L, service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00"), ServiceDetailStatus.IN_PROGRESS);
        VisitServiceDetail vaccine = detail(2L, 100L, service(2L, "VAC_7B", "VACCINATION", "150000.00"), ServiceDetailStatus.PENDING);
        when(detailRepository.findByVisitIdOrderByIdAsc(lid(100L))).thenReturn(List.of(exam, vaccine));
        ServiceInvoice examInvoice = ServiceInvoice.builder()
                .id(lid(50L)).visitServiceDetailId(lid(1L)).unitPrice(new BigDecimal("100000.00")).vatRate(BigDecimal.ZERO).build();
        ServiceInvoice vaccineInvoice = ServiceInvoice.builder()
                .id(lid(51L)).visitServiceDetailId(lid(2L)).unitPrice(new BigDecimal("150000.00")).vatRate(BigDecimal.ZERO).build();
        when(invoiceRepository.findByVisitServiceDetailId(lid(1L))).thenReturn(Optional.of(examInvoice));
        when(invoiceRepository.findByVisitServiceDetailId(lid(2L))).thenReturn(Optional.of(vaccineInvoice));

        VisitResponse response = service.cancelExam(lid(100L), new ReasonRequest("  Chuyển viện cấp cứu  "));

        assertThat(response.status()).isEqualTo(VisitStatus.CANCELLED);
        assertThat(exam.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(vaccine.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(examInvoice.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(vaccineInvoice.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(lastHistory().getNotes()).isEqualTo("Chuyển viện cấp cứu");   // lý do ghi nguyên văn vào lịch sử
        assertThat(lastHistory().getToStatus()).isEqualTo(VisitStatus.CANCELLED);
        verify(ticketIssuer).cancelActiveTickets(lid(100L), "Chuyển viện cấp cứu");   // phiếu -> CANCELLED (lịch sử trong giao dịch, xóa Redis sau commit)
        ArgumentCaptor<DomainEvent> cancelled = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).write(eq("VISIT"), eq(lid(100L)), cancelled.capture());
        assertThat(((VisitCancelledEvent) cancelled.getValue()).reason()).isEqualTo("Chuyển viện cấp cứu");   // reporting tăng cancelled_visits
    }

    @Test
    void cancelExam_refusedInPhaseTwo_afterAnInRoomServiceWasDone_orByAnotherDoctor() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        ReasonRequest reason = new ReasonRequest("Khách xin chuyển viện");
        // Giai đoạn 2: đã có kết quả cận lâm sàng cần thu phí
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.IN_PROGRESS, DOCTOR)));
        lenient().when(historyRepository.existsByVisitIdAndToStatus(lid(100L), VisitStatus.WAITING_RESULTS)).thenReturn(true);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancelExam(lid(100L), reason));
        // Đã thực hiện một dịch vụ tại phòng: không hủy, phải chốt để thu phí
        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(visit(101L, VisitStatus.IN_PROGRESS, DOCTOR)));
        when(detailRepository.countByVisitAndKindAndStatuses(lid(101L), ServiceKind.IN_ROOM,
                List.of(ServiceDetailStatus.COMPLETED))).thenReturn(1L);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancelExam(lid(101L), reason));
        // Bác sĩ khác / ca đã khóa
        when(visitRepository.findByIdForUpdate(lid(102L))).thenReturn(Optional.of(visit(102L, VisitStatus.IN_PROGRESS, OTHER_DOCTOR)));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.cancelExam(lid(102L), reason));
        when(visitRepository.findByIdForUpdate(lid(103L))).thenReturn(Optional.of(visit(103L, VisitStatus.WAITING_PAYMENT, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancelExam(lid(103L), reason));
        verify(historyRepository, never()).save(any());
    }

    @Test
    void cancel_waitingVisit_byReceptionist_T03() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        PetVisit visit = visit(100L, VisitStatus.WAITING, null);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));

        VisitResponse response = service.cancel(lid(100L), new ReasonRequest("  Khách bận việc xin về  "));

        assertThat(response.status()).isEqualTo(VisitStatus.CANCELLED);
        assertThat(response.locked()).isTrue();
        // Không còn cột cancel_reason: lý do ghi nguyên văn vào notes của dòng lịch sử, VisitTimeline suy ra khi hiển thị
        assertThat(lastHistory().getNotes()).isEqualTo("Khách bận việc xin về");
        assertThat(lastHistory().getToStatus()).isEqualTo(VisitStatus.CANCELLED);
        verify(ticketIssuer).cancelActiveTickets(lid(100L), "Khách bận việc xin về");
        ArgumentCaptor<DomainEvent> cancelled = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).write(eq("VISIT"), eq(lid(100L)), cancelled.capture());
        assertThat(cancelled.getValue()).isInstanceOf(VisitCancelledEvent.class);
    }

    @Test
    void cancel_isOnlyForTheReceptionist_andOnlyWhileWaiting() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.cancel(lid(100L), new ReasonRequest("x")));   // Bác sĩ dùng cancel-exam

        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(visit(101L, VisitStatus.IN_PROGRESS, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancel(lid(101L), new ReasonRequest("x")));
        when(visitRepository.findByIdForUpdate(lid(102L))).thenReturn(Optional.of(visit(102L, VisitStatus.WAITING_RESULTS, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancel(lid(102L), new ReasonRequest("x")));   // khách bỏ về = abandon
        when(visitRepository.findByIdForUpdate(lid(103L))).thenReturn(Optional.of(visit(103L, VisitStatus.WAITING_PAYMENT, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancel(lid(103L), new ReasonRequest("x")));
    }

    // ================================================================== Mở lại hồ sơ (T14)

    @Test
    void reopen_byTheAssignedDoctor_movesBackToInProgress_withoutTouchingBilling() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));

        VisitResponse response = service.reopen(lid(100L), new ReasonRequest("  Nhập sai liều thuốc "));

        assertThat(response.status()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(response.locked()).isFalse();            // hồ sơ được mở khóa để sửa
        PetVisitStatusHistory history = lastHistory();
        assertThat(history.getFromStatus()).isEqualTo(VisitStatus.WAITING_PAYMENT);
        assertThat(history.getToStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(history.getChangedByUserId()).isEqualTo(DOCTOR);
        assertThat(history.getNotes()).contains("Nhập sai liều thuốc");
        verify(invoiceRepository, never()).save(any());   // hóa đơn dịch vụ giữ nguyên (PAID/PENDING)
    }

    @Test
    void reopen_byAdmin_isAllowedToo() {
        loginAs(ADMIN_ID, RoleCode.ADMIN);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR)));

        assertThat(service.reopen(lid(100L), new ReasonRequest("Điều chỉnh theo yêu cầu")).status()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(lastHistory().getChangedByUserId()).isEqualTo(ADMIN_ID);
    }

    @Test
    void reopen_refusedForOtherDoctorsOtherRolesWrongStatusOrABusyDoctor() {
        loginAs(OTHER_DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR)));
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.reopen(lid(100L), new ReasonRequest("x")));

        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.reopen(lid(100L), new ReasonRequest("x")));

        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findByIdForUpdate(lid(101L))).thenReturn(Optional.of(visit(101L, VisitStatus.COMPLETED, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.reopen(lid(101L), new ReasonRequest("x")));   // đã hoàn tất: khóa vĩnh viễn
        when(visitRepository.findByIdForUpdate(lid(102L))).thenReturn(Optional.of(visit(102L, VisitStatus.IN_PROGRESS, DOCTOR)));
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.reopen(lid(102L), new ReasonRequest("x")));

        when(visitRepository.findByIdForUpdate(lid(103L))).thenReturn(Optional.of(visit(103L, VisitStatus.WAITING_PAYMENT, DOCTOR)));
        when(visitRepository.existsByDoctorIdAndStatus(DOCTOR, VisitStatus.IN_PROGRESS)).thenReturn(true);
        assertError(ErrorCode.DOCTOR_NOT_AVAILABLE, () -> service.reopen(lid(103L), new ReasonRequest("x")));
        when(visitRepository.findByIdForUpdate(lid(404L))).thenReturn(Optional.empty());
        assertError(ErrorCode.VISIT_NOT_FOUND, () -> service.reopen(lid(404L), new ReasonRequest("x")));
        verify(historyRepository, never()).save(any());
    }

    // ================================================================== fixtures

    private void stubSaveVisit() {
        when(visitRepository.saveAndFlush(any(PetVisit.class))).thenAnswer(inv -> {
            PetVisit saved = inv.getArgument(0);
            saved.setId(lid(100L));
            return saved;
        });
    }

    private void stubSaveDetail() {
        when(detailRepository.saveAndFlush(any(VisitServiceDetail.class))).thenAnswer(inv -> {
            VisitServiceDetail saved = inv.getArgument(0);
            saved.setId(lid(500L));
            return saved;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_returnsPagedVisits_newestFirst_andClampsPageSize() {
        PetVisit visit = visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR);
        when(visitRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(visit), PageRequest.of(0, 100), 1));

        var page = service.search(VisitStatus.WAITING_PAYMENT, LocalDate.of(2026, 10, 30), DOCTOR, null, "vis-", -3, 5000);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).status()).isEqualTo(VisitStatus.WAITING_PAYMENT);
        assertThat(page.getTotalElements()).isEqualTo(1);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(visitRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();          // trang âm bị đưa về 0
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);      // kích thước tối đa 100
        assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
    }

    private void stubDoctor(UUID id, RoleCode role, String status, String room) {
        when(authClient.getUser(id)).thenReturn(ApiResponse.success(
                new UserSummaryResponse(id, "BS " + id, "0911111111", role, List.of(role), room, status)));
    }

    private static AppointmentSummaryResponse appointment(String status, LocalDate date, UUID petId) {
        return new AppointmentSummaryResponse(apid(7), "APT-20261030-00007", petId, OWNER, date, null, null, "Khám tổng quát", status);
    }

    private PetVisitStatusHistory lastHistory() {
        ArgumentCaptor<PetVisitStatusHistory> captor = ArgumentCaptor.forClass(PetVisitStatusHistory.class);
        verify(historyRepository, Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
