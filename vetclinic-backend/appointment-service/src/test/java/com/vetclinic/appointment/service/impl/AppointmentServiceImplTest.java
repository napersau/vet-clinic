package com.vetclinic.appointment.service.impl;

import com.vetclinic.appointment.client.AuthClient;
import com.vetclinic.appointment.client.PetClient;
import com.vetclinic.appointment.config.BookingProperties;
import com.vetclinic.appointment.constant.AppointmentStatus;
import com.vetclinic.appointment.constant.BookingChannel;
import com.vetclinic.appointment.dto.request.CancelAppointmentRequest;
import com.vetclinic.appointment.dto.request.CreateAppointmentRequest;
import com.vetclinic.appointment.dto.request.RescheduleAppointmentRequest;
import com.vetclinic.appointment.dto.response.AppointmentResponse;
import com.vetclinic.appointment.dto.response.PetSummaryResponse;
import com.vetclinic.appointment.dto.response.UserSummaryResponse;
import com.vetclinic.appointment.entity.Appointment;
import com.vetclinic.appointment.entity.AppointmentStatusHistory;
import com.vetclinic.appointment.mapper.AppointmentMapperImpl;
import com.vetclinic.appointment.repository.AppointmentRepository;
import com.vetclinic.appointment.repository.AppointmentStatusHistoryRepository;
import com.vetclinic.appointment.service.AppointmentFilter;
import com.vetclinic.appointment.service.SlotLockService;
import com.vetclinic.appointment.service.SlotPolicy;
import com.vetclinic.common.event.DomainEvent;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.AppointmentBookedEvent;
import com.vetclinic.common.event.AppointmentNoShowEvent;
import com.vetclinic.common.event.AppointmentStatusChangedEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.UUID;
import java.util.Collection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceImplTest {

    private static final List<String> STANDARD_SLOTS = List.of("08:00", "08:30", "09:00", "09:30", "10:00", "10:30",
            "11:00", "13:30", "14:00", "14:30", "15:00", "15:30", "16:00", "16:30");

    // 2026-10-30 09:00 giờ Việt Nam
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final UUID OWNER = uid(5);
    private static final UUID OTHER_OWNER = uid(6);
    private static final UUID RECEPTIONIST = uid(9);
    private static final UUID PET = uid(110);
    private static final UUID PET_2 = uid(111);
    private static final UUID OWNER_77 = uid(77);
    private static final UUID PET_77 = uid(770);

    /** UUID cố định của lịch hẹn thử (019a9442-d830-7000-8000-0000000000NN). */
    private static UUID aid(long n) {
        return UUID.fromString(String.format("019a9442-d830-7000-8000-%012d", n));
    }

    /** UUID demo cố định, cùng dạng với dữ liệu seed (019a9442-d810-7000-8000-0000000000NN). */
    private static UUID uid(long n) {
        return UUID.fromString(String.format("019a9442-d810-7000-8000-%012d", n));
    }

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private AppointmentStatusHistoryRepository historyRepository;
    @Mock
    private AuthClient authClient;
    @Mock
    private PetClient petClient;
    @Mock
    private SlotLockService slotLockService;
    @Mock
    private OutboxWriter outboxWriter;

    private AppointmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = newService();
        // Khóa khung giờ giả: chạy thẳng action, giống khi lấy được khóa
        lenient().when(slotLockService.runLocked(anyCollection(), any())).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(1)).get());
        // Đặt lịch luôn hỏi clinical-service: thú cưng phải thuộc đúng chủ nuôi; tên thú cưng + tên / SĐT chủ được chụp thành snapshot.
        // PET thuộc OWNER, PET_2 thuộc OTHER_OWNER, PET_77 thuộc OWNER_77 (lễ tân đặt hộ)
        lenient().when(petClient.getPet(PET)).thenReturn(ApiResponse.success(new PetSummaryResponse(PET, OWNER, "Lu", "Hoàng Thị Lan", "0912000008")));
        lenient().when(petClient.getPet(PET_2)).thenReturn(ApiResponse.success(new PetSummaryResponse(PET_2, OTHER_OWNER, "Mun", "Phạm Văn Nam", "0912000009")));
        lenient().when(petClient.getPet(PET_77)).thenReturn(ApiResponse.success(new PetSummaryResponse(PET_77, OWNER_77, "Cún", "Chủ Nuôi", "0944444444")));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** Mọi sự kiện đã được ghi vào outbox trong test (theo thứ tự ghi). */
    private List<DomainEvent> writtenEvents() {
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter, Mockito.atLeastOnce()).write(eq("APPOINTMENT"), any(), captor.capture());
        return captor.getAllValues();
    }

    private void verifyNoOutbox() {
        verify(outboxWriter, never()).write(any(), any(), any(DomainEvent.class));
    }

    private AppointmentServiceImpl newService() {
        BookingProperties properties = new BookingProperties("Asia/Ho_Chi_Minh", 30,
                STANDARD_SLOTS, 30, 2, 60, 5);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new AppointmentServiceImpl(appointmentRepository, historyRepository, authClient, petClient,
                new SlotPolicy(properties, CLOCK, authClient), slotLockService, transactionManager, outboxWriter,
                new AppointmentMapperImpl(), properties, CLOCK);
    }

    // ================================================================== tạo lịch hẹn

    @Test
    void create_byOwner_savesPendingOnlineAppointmentAndPublishesEvent() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        stubCapacity(2);
        stubSave();
        when(appointmentRepository.countByAppointmentDateAndStartTimeAndStatusIn(
                TOMORROW, LocalTime.of(9, 0), AppointmentStatus.OCCUPYING)).thenReturn(1L);

        // Body cố tình gửi ownerId và kênh khác: phải bị bỏ qua, chủ nuôi chỉ đặt cho chính mình qua ONLINE
        AppointmentResponse response = service.create(new CreateAppointmentRequest(
                PET, OTHER_OWNER, TOMORROW, LocalTime.of(9, 0), "  Chó bỏ ăn 2 ngày  ", BookingChannel.PHONE));

        assertThat(response.status()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(response.bookingChannel()).isEqualTo(BookingChannel.ONLINE);
        assertThat(response.ownerId()).isEqualTo(OWNER);
        assertThat(response.reason()).isEqualTo("Chó bỏ ăn 2 ngày");
        assertThat(response.startTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(response.endTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(response.appointmentCode()).matches("APT-20261030-[A-Z2-9]{6}");   // đuôi ngẫu nhiên (khóa chính là UUID)
        // Snapshot chụp từ clinical-service lúc đặt: danh sách lịch hẹn không phải gọi chéo service
        assertThat(response.petName()).isEqualTo("Lu");
        assertThat(response.ownerName()).isEqualTo("Hoàng Thị Lan");
        assertThat(response.ownerPhone()).isEqualTo("0912000008");
        assertThat(response.confirmedBy()).isNull();

        AppointmentStatusHistory history = lastHistory();
        assertThat(history.getFromStatus()).isNull();
        assertThat(history.getToStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(history.getChangedByUserId()).isEqualTo(OWNER);

        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter, Mockito.times(1)).write(eq("APPOINTMENT"), any(), event.capture());   // chỉ appointment.booked (v3.0)
        AppointmentBookedEvent booked = (AppointmentBookedEvent) event.getValue();
        assertThat(booked.routingKey()).isEqualTo("appointment.booked");
        assertThat(booked.appointmentId()).isEqualTo(aid(100));
        assertThat(booked.ownerId()).isEqualTo(OWNER);
        assertThat(booked.status()).isEqualTo("PENDING");
    }

    @Test
    void create_takesBothSlotAndPetDayLocks() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        stubCapacity(2);
        stubSave();

        service.create(new CreateAppointmentRequest(PET, null, TOMORROW, LocalTime.of(9, 0), "Khám", null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> locks = ArgumentCaptor.forClass(Collection.class);
        verify(slotLockService).runLocked(locks.capture(), any());
        assertThat(locks.getValue()).containsExactlyInAnyOrder(
                SlotLockService.slotLock(TOMORROW, LocalTime.of(9, 0)),
                SlotLockService.petDayLock(PET, TOMORROW));
    }

    @Test
    void create_whenSlotIsFull_throwsCapacityExceededAndSavesNothing() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        stubCapacity(2);   // 2 bác sĩ ACTIVE => 4 chỗ mỗi khung
        when(appointmentRepository.countByAppointmentDateAndStartTimeAndStatusIn(
                TOMORROW, LocalTime.of(9, 0), AppointmentStatus.OCCUPYING)).thenReturn(4L);

        assertThatThrownBy(() -> service.create(
                new CreateAppointmentRequest(PET, null, TOMORROW, LocalTime.of(9, 0), "Khám", null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.SLOT_CAPACITY_EXCEEDED);

        verify(appointmentRepository, never()).saveAndFlush(any());
        verifyNoOutbox();
    }

    @Test
    void create_capacityIsTwoAppointmentsPerActiveDoctor() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        stubCapacity(1);   // 1 bác sĩ ACTIVE => 2 chỗ mỗi khung (FR-13: N_active × 2)
        stubSave();

        when(appointmentRepository.countByAppointmentDateAndStartTimeAndStatusIn(
                TOMORROW, LocalTime.of(9, 0), AppointmentStatus.OCCUPYING)).thenReturn(1L);
        // Đã có 1 lịch: còn chỗ thứ 2 (trước v3.0 khung này đã kín vì sức chứa chỉ bằng số bác sĩ)
        assertThat(service.create(new CreateAppointmentRequest(PET, null, TOMORROW, LocalTime.of(9, 0), "Khám", null)).status())
                .isEqualTo(AppointmentStatus.PENDING);

        when(appointmentRepository.countByAppointmentDateAndStartTimeAndStatusIn(
                TOMORROW, LocalTime.of(9, 0), AppointmentStatus.OCCUPYING)).thenReturn(2L);
        assertThatThrownBy(() -> service.create(
                new CreateAppointmentRequest(PET, null, TOMORROW, LocalTime.of(9, 0), "Khám", null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.SLOT_CAPACITY_EXCEEDED);
    }

    @Test
    void create_withNoActiveDoctors_isRefusedWithoutTakingAnyLock() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        stubCapacity(0);

        assertThatThrownBy(() -> service.create(
                new CreateAppointmentRequest(PET, null, TOMORROW, LocalTime.of(9, 0), "Khám", null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.SLOT_CAPACITY_EXCEEDED);

        verify(slotLockService, never()).runLocked(anyCollection(), any());
    }

    @Test
    void create_samePetSameDay_throwsDuplicate() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        stubCapacity(2);
        when(appointmentRepository.existsByPetIdAndAppointmentDateAndStatusIn(
                PET, TOMORROW, AppointmentStatus.OCCUPYING)).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new CreateAppointmentRequest(PET, null, TOMORROW, LocalTime.of(10, 0), "Khám", null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_APPOINTMENT);
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_byReceptionist_isConfirmedImmediatelyWithConfirmer() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(authClient.getUser(uid(77))).thenReturn(ApiResponse.success(
                new UserSummaryResponse(uid(77), "Chủ Nuôi", "0944444444", RoleCode.PET_OWNER, List.of(RoleCode.PET_OWNER), "ACTIVE")));
        stubCapacity(1);
        stubSave();

        AppointmentResponse response = service.create(new CreateAppointmentRequest(
                PET_77, uid(77), TOMORROW, LocalTime.of(14, 0), "Khám tổng quát", BookingChannel.PHONE));
        assertThat(response.ownerName()).isEqualTo("Chủ Nuôi");

        assertThat(response.status()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(response.bookingChannel()).isEqualTo(BookingChannel.PHONE);
        assertThat(response.ownerId()).isEqualTo(uid(77));
        // Người tạo/duyệt nằm ở lịch sử (appointments không còn confirmed_by/confirmed_at)
        assertThat(lastHistory().getToStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(lastHistory().getChangedByUserId()).isEqualTo(RECEPTIONIST);
    }

    @Test
    void create_byReceptionist_requiresOwnerAndPhoneOrWalkInChannel() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);

        assertThatThrownBy(() -> service.create(new CreateAppointmentRequest(
                PET, null, TOMORROW, LocalTime.of(14, 0), "Khám", BookingChannel.PHONE)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA);
        assertThatThrownBy(() -> service.create(new CreateAppointmentRequest(
                PET, uid(77), TOMORROW, LocalTime.of(14, 0), "Khám", BookingChannel.ONLINE)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA);
        assertThatThrownBy(() -> service.create(new CreateAppointmentRequest(
                PET, uid(77), TOMORROW, LocalTime.of(14, 0), "Khám", null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA);
    }

    @Test
    void create_byReceptionist_forUserThatIsNotAnActivePetOwner_isRejected() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(authClient.getUser(uid(3))).thenReturn(ApiResponse.success(
                new UserSummaryResponse(uid(3), "Bác sĩ", "0911111111", RoleCode.VETERINARIAN, List.of(RoleCode.VETERINARIAN), "ACTIVE")));
        when(authClient.getUser(uid(4))).thenReturn(ApiResponse.success(
                new UserSummaryResponse(uid(4), "Chủ cũ", "0922222222", RoleCode.PET_OWNER, List.of(RoleCode.PET_OWNER), "LOCKED")));

        for (UUID ownerId : List.of(uid(3), uid(4))) {
            assertThatThrownBy(() -> service.create(new CreateAppointmentRequest(
                    PET, ownerId, TOMORROW, LocalTime.of(14, 0), "Khám", BookingChannel.WALK_IN)))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA);
        }
    }

    @Test
    void create_byOtherRoles_isForbidden() {
        loginAs(uid(3), RoleCode.VETERINARIAN);

        assertThatThrownBy(() -> service.create(
                new CreateAppointmentRequest(PET, null, TOMORROW, LocalTime.of(9, 0), "Khám", null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    @Test
    void create_rejectsInvalidSlotPastTimeAndDatesOutsideHorizon() {
        loginAs(OWNER, RoleCode.PET_OWNER);

        // Không phải khung chuẩn
        assertCreateFails(TOMORROW, LocalTime.of(9, 15), ErrorCode.INVALID_APPOINTMENT_SLOT);
        // Giờ nghỉ trưa
        assertCreateFails(TOMORROW, LocalTime.of(12, 30), ErrorCode.INVALID_APPOINTMENT_SLOT);
        // Hôm qua
        assertCreateFails(TODAY.minusDays(1), LocalTime.of(9, 0), ErrorCode.INVALID_INPUT_DATA);
        // Hôm nay nhưng khung đã bắt đầu (bây giờ 09:00)
        assertCreateFails(TODAY, LocalTime.of(9, 0), ErrorCode.INVALID_INPUT_DATA);
        assertCreateFails(TODAY, LocalTime.of(8, 30), ErrorCode.INVALID_INPUT_DATA);
        // Quá 60 ngày
        assertCreateFails(TODAY.plusDays(61), LocalTime.of(9, 0), ErrorCode.INVALID_INPUT_DATA);
    }

    @Test
    void create_rejectsPetBelongingToSomeoneElse_alwaysVerified() {
        loginAs(OWNER, RoleCode.PET_OWNER);

        assertThatThrownBy(() -> service.create(
                new CreateAppointmentRequest(PET_2, null, TOMORROW, LocalTime.of(9, 0), "Khám", null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.PET_NOT_FOUND);
        verify(slotLockService, never()).runLocked(anyCollection(), any());
    }

    @Test
    void create_whenClinicalServiceIsDown_failsClosedWith503AndTakesNoLock() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        when(petClient.getPet(PET)).thenThrow(new RuntimeException("clinical down"));

        assertThatThrownBy(() -> service.create(
                new CreateAppointmentRequest(PET, null, TOMORROW, LocalTime.of(9, 0), "Khám", null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        verify(slotLockService, never()).runLocked(anyCollection(), any());
    }

    // ================================================================== duyệt, hủy

    @Test
    void confirm_movesPendingToConfirmedAndRecordsReceptionist() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.PENDING, TOMORROW, LocalTime.of(9, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        AppointmentResponse response = service.confirm(aid(1));

        assertThat(response.status()).isEqualTo(AppointmentStatus.CONFIRMED);
        AppointmentStatusHistory history = lastHistory();
        assertThat(history.getChangedByUserId()).isEqualTo(RECEPTIONIST);   // người duyệt nằm ở lịch sử, không còn cột confirmed_by
        assertThat(history.getFromStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(history.getToStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void confirm_isNotRepeatableAndOnlyForReceptionist() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.CONFIRMED, TOMORROW, LocalTime.of(9, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.confirm(aid(1)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_APPOINTMENT_STATUS);
        verify(historyRepository, never()).save(any());

        loginAs(OWNER, RoleCode.PET_OWNER);
        assertThatThrownBy(() -> service.confirm(aid(1)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
    }

    @Test
    void cancel_byOwnerWellBeforeAppointment_isAllowed() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.CONFIRMED, TOMORROW, LocalTime.of(10, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        AppointmentResponse response = service.cancel(aid(1), new CancelAppointmentRequest("  Bận việc đột xuất "));

        assertThat(response.status()).isEqualTo(AppointmentStatus.CANCELLED);
        // Lý do hủy ghi nguyên văn vào lịch sử (cột cancel_reason/cancelled_by đã bỏ ở v3.0)
        assertThat(lastHistory().getNotes()).isEqualTo("Bận việc đột xuất");
        assertThat(lastHistory().getChangedByUserId()).isEqualTo(OWNER);
    }

    @Test
    void cancel_byOwnerLessThanTwoHoursBefore_isRefused() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        // Bây giờ 09:00, hẹn 10:30 -> còn 1,5 giờ
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.CONFIRMED, TODAY, LocalTime.of(10, 30));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.cancel(aid(1), new CancelAppointmentRequest("Bận")))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.CANNOT_CANCEL_APPOINTMENT);
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        verify(historyRepository, never()).save(any());
    }

    @Test
    void cancel_byOwnerExactlyTwoHoursBefore_isStillAllowed() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.PENDING, TODAY, LocalTime.of(11, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        assertThat(service.cancel(aid(1), new CancelAppointmentRequest("Bận")).status())
                .isEqualTo(AppointmentStatus.CANCELLED);
    }

    @Test
    void cancel_ofSomeoneElsesAppointment_looksLikeNotFound() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        Appointment a = appointment(aid(1), OTHER_OWNER, AppointmentStatus.PENDING, TOMORROW, LocalTime.of(10, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.cancel(aid(1), new CancelAppointmentRequest("Bận")))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.APPOINTMENT_NOT_FOUND);
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.PENDING);
    }

    private AppointmentStatusChangedEvent publishedStatusEvent() {
        return writtenEvents().stream()
                .filter(AppointmentStatusChangedEvent.class::isInstance)
                .map(AppointmentStatusChangedEvent.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void confirm_notifiesTheOwner_UC17() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.PENDING, TOMORROW, LocalTime.of(9, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        service.confirm(aid(1));

        var event = publishedStatusEvent();
        assertThat(event.ownerId()).isEqualTo(OWNER);
        assertThat(event.status()).isEqualTo("CONFIRMED");
        assertThat(event.rescheduled()).isFalse();
        assertThat(event.actorUserId()).isEqualTo(RECEPTIONIST);
    }

    @Test
    void cancel_byReceptionistCarriesTheReasonToTheOwner_butOwnersOwnCancelDoesNot() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.PENDING, TOMORROW, LocalTime.of(9, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));
        service.cancel(aid(1), new CancelAppointmentRequest("Phòng khám bảo trì thiết bị"));

        var event = publishedStatusEvent();
        assertThat(event.status()).isEqualTo("CANCELLED");
        assertThat(event.reason()).isEqualTo("Phòng khám bảo trì thiết bị");

        Mockito.clearInvocations(outboxWriter);
        loginAs(OWNER, RoleCode.PET_OWNER);
        Appointment own = appointment(aid(2), OWNER, AppointmentStatus.CONFIRMED, TOMORROW, LocalTime.of(10, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(2))).thenReturn(Optional.of(own));
        lenient().when(appointmentRepository.findById(aid(2))).thenReturn(Optional.of(own));
        service.cancel(aid(2), new CancelAppointmentRequest("Bận việc đột xuất"));

        verifyNoOutbox();   // chủ nuôi tự hủy: đã thấy kết quả trên màn hình
    }

    @Test
    void systemNoShow_publishesOnlyTheDedicatedNoShowEvent_neverStatusChanged() {
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.CONFIRMED, LocalDate.of(2026, 10, 29), LocalTime.of(8, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        assertThat(service.markNoShowBySystem(aid(1))).isTrue();

        var written = writtenEvents();
        assertThat(written).hasSize(1);   // không để chủ nuôi nhận hai thông báo
        var event = (AppointmentNoShowEvent) written.get(0);
        assertThat(event.actorUserId()).isNull();
    }

    @Test
    void cancel_byReceptionist_ignoresTheTwoHourRule() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.PENDING, TODAY, LocalTime.of(9, 30));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        AppointmentResponse response = service.cancel(aid(1), new CancelAppointmentRequest("Phòng khám bảo trì thiết bị"));

        assertThat(response.status()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(lastHistory().getNotes()).isEqualTo("Phòng khám bảo trì thiết bị");
        assertThat(lastHistory().getChangedByUserId()).isEqualTo(RECEPTIONIST);
    }

    @Test
    void cancel_ofFinishedAppointment_isRefused() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        for (AppointmentStatus status : List.of(AppointmentStatus.CHECKED_IN, AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW)) {
            Appointment a = appointment(aid(1), OWNER, status, TOMORROW, LocalTime.of(10, 0));
            lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
            lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

            assertThatThrownBy(() -> service.cancel(aid(1), new CancelAppointmentRequest("Lý do")))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_APPOINTMENT_STATUS);
        }
    }

    // ================================================================== đổi lịch

    @Test
    void reschedule_byOwner_movesToFreeSlotKeepingStatusAndWritesAuditNote() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.CONFIRMED, TOMORROW, LocalTime.of(9, 0));
        when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));
        stubCapacity(2);

        AppointmentResponse response = service.reschedule(aid(1), new RescheduleAppointmentRequest(
                TOMORROW.plusDays(1), LocalTime.of(14, 30), "Đổi lịch công việc"));

        assertThat(response.status()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(response.appointmentDate()).isEqualTo(TOMORROW.plusDays(1));
        assertThat(response.startTime()).isEqualTo(LocalTime.of(14, 30));
        assertThat(response.endTime()).isEqualTo(LocalTime.of(15, 0));
        AppointmentStatusHistory history = lastHistory();
        assertThat(history.getFromStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(history.getToStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(history.getNotes()).contains("2026-10-31 09:00").contains("2026-11-01 14:30").contains("Đổi lịch công việc");
    }

    @Test
    void reschedule_toFullSlot_isRefusedAndAppointmentUnchanged() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.PENDING, TOMORROW, LocalTime.of(9, 0));
        when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));
        stubCapacity(2);   // 2 bác sĩ ACTIVE => 4 chỗ mỗi khung
        when(appointmentRepository.countByAppointmentDateAndStartTimeAndStatusIn(
                TOMORROW, LocalTime.of(10, 0), AppointmentStatus.OCCUPYING)).thenReturn(4L);

        assertThatThrownBy(() -> service.reschedule(aid(1), new RescheduleAppointmentRequest(TOMORROW, LocalTime.of(10, 0), null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.SLOT_CAPACITY_EXCEEDED);
        assertThat(a.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        verify(historyRepository, never()).save(any());
    }

    @Test
    void reschedule_toTheSameSlot_isRefused() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.PENDING, TOMORROW, LocalTime.of(9, 0));
        when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));
        stubCapacity(2);

        assertThatThrownBy(() -> service.reschedule(aid(1), new RescheduleAppointmentRequest(TOMORROW, LocalTime.of(9, 0), null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA);
    }

    @Test
    void reschedule_byOwnerLessThanTwoHoursBefore_isRefused() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.CONFIRMED, TODAY, LocalTime.of(10, 30));
        when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));
        stubCapacity(2);

        assertThatThrownBy(() -> service.reschedule(aid(1), new RescheduleAppointmentRequest(TOMORROW, LocalTime.of(9, 0), null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.CANNOT_CANCEL_APPOINTMENT);
    }

    @Test
    void reschedule_ofSomeoneElsesAppointment_looksLikeNotFound() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(
                appointment(aid(1), OTHER_OWNER, AppointmentStatus.PENDING, TOMORROW, LocalTime.of(9, 0))));

        assertThatThrownBy(() -> service.reschedule(aid(1), new RescheduleAppointmentRequest(TOMORROW, LocalTime.of(10, 0), null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.APPOINTMENT_NOT_FOUND);
        verify(slotLockService, never()).runLocked(anyCollection(), any());
    }

    // ================================================================== vắng mặt (EX-01)

    @Test
    void markNoShowBySystem_afterGracePeriod_movesConfirmedToNoShowWithSystemActor() {
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.CONFIRMED, TODAY.minusDays(1), LocalTime.of(9, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        assertThat(service.markNoShowBySystem(aid(1))).isTrue();

        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
        AppointmentStatusHistory history = lastHistory();
        assertThat(history.getFromStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(history.getToStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
        assertThat(history.getChangedByUserId()).isNull();   // null = hệ thống
    }

    @Test
    void markNoShowBySystem_beforeGracePeriodEnds_doesNothing() {
        // Hẹn 08:30 + 30 phút = 09:00, bây giờ đúng 09:00: chưa quá hạn
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.CONFIRMED, TODAY, LocalTime.of(8, 30));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        assertThat(service.markNoShowBySystem(aid(1))).isFalse();
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        verify(historyRepository, never()).save(any());
    }

    @Test
    void markNoShowBySystem_isDueAtStartTimePlus30Minutes_notAtEndOfSlotPlus30() {
        // Hẹn 08:00, bây giờ 09:00. v3.0 (FR-17): hạn = 08:00 + 30' = 08:30 => đã quá hạn.
        // (v2 tính từ giờ KẾT THÚC khung: 08:30 + 30' = 09:00 => chưa quá hạn, nên test này phân biệt được hai công thức)
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.CONFIRMED, TODAY, LocalTime.of(8, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        assertThat(service.markNoShowBySystem(aid(1))).isTrue();
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);
    }

    @Test
    void noShow_publishesTheDedicatedNoShowEvent_withNoActorForTheSystemScan() {
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.CONFIRMED, TODAY.minusDays(1), LocalTime.of(9, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        assertThat(service.markNoShowBySystem(aid(1))).isTrue();

        var noShow = writtenEvents().stream()
                .filter(AppointmentNoShowEvent.class::isInstance)
                .map(AppointmentNoShowEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(noShow.routingKey()).isEqualTo("appointment.no_show");
        assertThat(noShow.actorUserId()).isNull();                 // null = hệ thống tự quét
        assertThat(noShow.ownerId()).isEqualTo(OWNER);             // appointments.owner_id
        assertThat(noShow.appointmentId()).isEqualTo(aid(1));
    }

    @Test
    void markNoShowBySystem_neverTouchesAppointmentsThatAreNoLongerConfirmed() {
        // Khách vừa check-in đúng lúc tiến trình quét chạy: không được đè sang NO_SHOW
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.CHECKED_IN, TODAY.minusDays(1), LocalTime.of(9, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        assertThat(service.markNoShowBySystem(aid(1))).isFalse();
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
        verify(appointmentRepository, never()).save(any());
    }

    // ================================================================== PENDING quá hạn

    @Test
    void expirePendingBySystem_afterTheSlotEnded_cancelsAndFreesTheSlotWithSystemActor() {
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.PENDING, TODAY, LocalTime.of(8, 0));   // khung 08:00-08:30, bây giờ 09:00
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        assertThat(service.expirePendingBySystem(aid(1))).isTrue();

        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        AppointmentStatusHistory history = lastHistory();
        assertThat(history.getNotes()).contains("chưa xác nhận");
        assertThat(history.getFromStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(history.getToStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(history.getChangedByUserId()).isNull();
    }

    @Test
    void expirePendingBySystem_doesNothingWhileTheSlotIsStillOpen() {
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.PENDING, TODAY, LocalTime.of(9, 0));   // khung 09:00-09:30
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        assertThat(service.expirePendingBySystem(aid(1))).isFalse();
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.PENDING);
        verify(historyRepository, never()).save(any());
    }

    @Test
    void expirePendingBySystem_neverTouchesAppointmentsApprovedMeanwhile() {
        // Lễ tân vừa duyệt đúng lúc tiến trình quét chạy: không được hủy lịch đã CONFIRMED
        Appointment confirmed = appointment(aid(1), OWNER, AppointmentStatus.CONFIRMED, TODAY.minusDays(1), LocalTime.of(9, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(confirmed));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(confirmed));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(2))).thenReturn(Optional.empty());

        assertThat(service.expirePendingBySystem(aid(1))).isFalse();
        assertThat(service.expirePendingBySystem(aid(2))).isFalse();
        assertThat(confirmed.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void markNoShow_byReceptionist_requiresGracePeriodToHavePassed() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Appointment late = appointment(aid(1), OWNER, AppointmentStatus.CONFIRMED, TODAY.minusDays(1), LocalTime.of(9, 0));
        Appointment early = appointment(aid(2), OWNER, AppointmentStatus.CONFIRMED, TODAY, LocalTime.of(8, 30));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(late));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(late));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(2))).thenReturn(Optional.of(early));
        lenient().when(appointmentRepository.findById(aid(2))).thenReturn(Optional.of(early));

        assertThat(service.markNoShow(aid(1)).status()).isEqualTo(AppointmentStatus.NO_SHOW);
        assertThat(lastHistory().getChangedByUserId()).isEqualTo(RECEPTIONIST);

        assertThatThrownBy(() -> service.markNoShow(aid(2)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_APPOINTMENT_STATUS);
        assertThat(early.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    // ================================================================== check-in (từ sự kiện của clinical-service)

    @Test
    void markCheckedIn_movesConfirmedToCheckedInWithReceptionistAsActor() {
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.CONFIRMED, TODAY, LocalTime.of(9, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
        lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

        service.markCheckedIn(aid(1), RECEPTIONIST, "VIS-2026-0001");

        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
        AppointmentStatusHistory history = lastHistory();
        assertThat(history.getChangedByUserId()).isEqualTo(RECEPTIONIST);
        assertThat(history.getNotes()).contains("VIS-2026-0001");
    }

    @Test
    void markCheckedIn_isIdempotentAndIgnoresInconsistentStates() {
        for (AppointmentStatus status : List.of(AppointmentStatus.CHECKED_IN, AppointmentStatus.NO_SHOW,
                AppointmentStatus.CANCELLED, AppointmentStatus.PENDING)) {
            Appointment a = appointment(aid(1), OWNER, status, TODAY, LocalTime.of(9, 0));
            lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));
            lenient().when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(a));

            service.markCheckedIn(aid(1), RECEPTIONIST, "VIS-2026-0001");

            assertThat(a.getStatus()).isEqualTo(status);
        }
        verify(historyRepository, never()).save(any());
    }

    @Test
    void markCheckedIn_forUnknownAppointment_failsSoTheMessageGoesToTheDeadLetterQueue() {
        lenient().when(appointmentRepository.findByIdForUpdate(aid(99))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markCheckedIn(aid(99), RECEPTIONIST, "VIS-2026-0001"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.APPOINTMENT_NOT_FOUND);
    }

    @Test
    void markCheckedIn_afterNoShow_keepsNoShowAndDoesNotRecordAnything_FR18() {
        Appointment a = appointment(aid(1), OWNER, AppointmentStatus.NO_SHOW, TODAY, LocalTime.of(8, 0));
        lenient().when(appointmentRepository.findByIdForUpdate(aid(1))).thenReturn(Optional.of(a));

        service.markCheckedIn(aid(1), RECEPTIONIST, "VISIT-2026-0001");

        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.NO_SHOW);   // lịch giữ NO_SHOW, khách được tiếp nhận như vãng lai
        verify(historyRepository, never()).save(any());
        verify(appointmentRepository, never()).save(any());
    }

    // ================================================================== tra cứu

    @Test
    void getById_hidesOtherOwnersAppointments() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        when(appointmentRepository.findById(aid(1))).thenReturn(Optional.of(
                appointment(aid(1), OTHER_OWNER, AppointmentStatus.PENDING, TOMORROW, LocalTime.of(9, 0))));
        when(appointmentRepository.findById(aid(2))).thenReturn(Optional.of(
                appointment(aid(2), OWNER, AppointmentStatus.PENDING, TOMORROW, LocalTime.of(9, 0))));

        assertThatThrownBy(() -> service.getById(aid(1)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(ErrorCode.APPOINTMENT_NOT_FOUND);
        assertThat(service.getById(aid(2)).id()).isEqualTo(aid(2));

        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        assertThat(service.getById(aid(1)).ownerId()).isEqualTo(OTHER_OWNER);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void search_byOwner_ignoresTheOwnerIdParameterAndFiltersByTheCallersOwnOwnerId() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        var captor = ArgumentCaptor.forClass(Specification.class);
        when(appointmentRepository.findAll(captor.capture(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        // Cố ý gửi ownerId của người khác: phải bị bỏ qua
        service.search(new AppointmentFilter(OTHER_OWNER, null, null, null, null, null, null, false, 0, 20));

        // Lọc thẳng bằng cột owner_id của appointments (không gọi clinical-service): điều kiện là owner_id = người đang đăng nhập
        Specification<Appointment> applied = captor.getValue();
        Root<Appointment> root = mock(Root.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        jakarta.persistence.criteria.Path ownerPath = mock(jakarta.persistence.criteria.Path.class);
        when(root.get("ownerId")).thenReturn(ownerPath);
        when(cb.equal(any(), any(Object.class))).thenReturn(mock(Predicate.class));
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));
        applied.toPredicate(root, mock(CriteriaQuery.class), cb);
        verify(cb).equal(ownerPath, OWNER);
        verify(cb, never()).equal(ownerPath, OTHER_OWNER);
        verify(petClient, never()).getPet(any());
    }

    @Test
    void search_byReceptionistWithoutOwnerFilter_returnsEveryAppointmentFromSnapshots() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(appointmentRepository.findAll(any(Specification.class),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        appointment(aid(1), OWNER, AppointmentStatus.PENDING, TOMORROW, LocalTime.of(9, 0)))));

        var page = service.search(new AppointmentFilter(
                null, null, null, null, null, null, null, false, 0, 20));

        verify(petClient, never()).getPet(any());
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).petName()).isEqualTo("Lu");   // snapshot trên appointments
        assertThat(page.getItems().get(0).ownerName()).isEqualTo("Hoàng Thị Lan");
        assertThat(page.getItems().get(0).endTime()).isEqualTo(LocalTime.of(9, 30));   // endTime suy ra từ giờ bắt đầu
    }

    // ================================================================== snapshot (consumer sự kiện)

    @Test
    void refreshOwnerSnapshot_updatesEveryAppointmentOfTheOwnerWithOneStatement() {
        when(appointmentRepository.updateOwnerSnapshot(OWNER, "Hoàng Lan", "0912999999")).thenReturn(3);

        assertThat(service.refreshOwnerSnapshot(OWNER, "Hoàng Lan", "0912999999")).isEqualTo(3);
        // thiếu dữ liệu thì bỏ qua, không ghi gì
        assertThat(service.refreshOwnerSnapshot(OWNER, null, "0912999999")).isZero();
        assertThat(service.refreshOwnerSnapshot(null, "A", "B")).isZero();
        verify(appointmentRepository, Mockito.times(1)).updateOwnerSnapshot(any(), any(), any());
    }

    @Test
    void refreshPetSnapshot_renamesThePetAndMovesActiveAppointmentsToTheNewOwner() {
        when(appointmentRepository.updatePetNameSnapshot(PET, "Lu Lu")).thenReturn(2);
        when(appointmentRepository.transferOwner(PET, OTHER_OWNER, "Phạm Văn Nam", "0912000009", AppointmentStatus.MODIFIABLE)).thenReturn(1);

        assertThat(service.refreshPetSnapshot(PET, "Lu Lu", OTHER_OWNER, "Phạm Văn Nam", "0912000009")).isEqualTo(3);
        // chỉ đổi tên (chủ không đổi): vẫn gọi transferOwner nhưng câu UPDATE có điều kiện owner_id <> :ownerId nên không ảnh hưởng dòng nào
        when(appointmentRepository.updatePetNameSnapshot(PET_2, "Mun Mun")).thenReturn(1);
        assertThat(service.refreshPetSnapshot(PET_2, "Mun Mun", null, null, null)).isEqualTo(1);
        verify(appointmentRepository, never()).transferOwner(eq(PET_2), any(), any(), any(), any());
    }

    // ================================================================== fixtures

    private void assertCreateFails(LocalDate date, LocalTime start, ErrorCode expected) {
        assertThatThrownBy(() -> service.create(new CreateAppointmentRequest(PET, null, date, start, "Khám", null)))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(expected);
    }

    private void stubCapacity(long doctors) {
        when(authClient.getActiveDoctorCount()).thenReturn(ApiResponse.success(doctors));
    }

    private void stubSave() {
        when(appointmentRepository.saveAndFlush(any(Appointment.class))).thenAnswer(inv -> {
            Appointment saved = inv.getArgument(0);
            saved.setId(aid(100));
            return saved;
        });
    }

    private AppointmentStatusHistory lastHistory() {
        ArgumentCaptor<AppointmentStatusHistory> captor = ArgumentCaptor.forClass(AppointmentStatusHistory.class);
        verify(historyRepository, Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static Appointment appointment(UUID id, UUID ownerId, AppointmentStatus status, LocalDate date, LocalTime start) {
        return Appointment.builder()
                .id(id)
                .appointmentCode("APT-TEST-" + id)
                .petId(OTHER_OWNER.equals(ownerId) ? PET_2 : PET)
                .ownerId(ownerId)
                .petNameSnapshot(OTHER_OWNER.equals(ownerId) ? "Mun" : "Lu")
                .ownerNameSnapshot(OTHER_OWNER.equals(ownerId) ? "Phạm Văn Nam" : "Hoàng Thị Lan")
                .ownerPhoneSnapshot(OTHER_OWNER.equals(ownerId) ? "0912000009" : "0912000008")
                .appointmentDate(date)
                .startTime(start)
                .reason("Khám")
                .bookingChannel(BookingChannel.ONLINE)
                .status(status)
                .build();
    }

    private static void loginAs(UUID userId, RoleCode role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
