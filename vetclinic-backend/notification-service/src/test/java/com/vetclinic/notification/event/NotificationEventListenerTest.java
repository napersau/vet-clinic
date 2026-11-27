package com.vetclinic.notification.event;

import com.vetclinic.common.event.AppointmentBookedEvent;
import com.vetclinic.common.event.AppointmentNoShowEvent;
import com.vetclinic.common.event.AppointmentStatusChangedEvent;
import com.vetclinic.common.event.MedicineInvoicePaidEvent;
import com.vetclinic.common.event.MedicineLowStockEvent;
import com.vetclinic.common.event.ParaclinicalResultsReadyEvent;
import com.vetclinic.common.event.PatientCalledEvent;
import com.vetclinic.common.event.QueueTicketIssuedEvent;
import com.vetclinic.common.event.UserStatusChangedEvent;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.notification.config.NotificationProperties;
import com.vetclinic.notification.dto.UserSummary;
import com.vetclinic.notification.dto.VisitSummary;
import com.vetclinic.notification.model.NotificationMessage;
import com.vetclinic.notification.model.NotificationType;
import com.vetclinic.notification.model.Severity;
import com.vetclinic.notification.service.EmailService;
import com.vetclinic.notification.service.NotificationDispatcher;
import com.vetclinic.notification.service.PeopleLookup;
import com.vetclinic.notification.service.ProcessedEvents;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    /** UUID demo cố định, cùng dạng với dữ liệu seed (019a9442-d810-7000-8000-0000000000NN). */
    private static UUID uid(long n) {
        return UUID.fromString(String.format("019a9442-d810-7000-8000-%012d", n));
    }


    private static final Instant NOW = Instant.parse("2026-10-30T02:00:00Z");
    /** Đợt khám thử (khóa UUID). */
    private static final UUID VISIT_ID = uid(7);
    private static final UUID OWNER = uid(5);
    private static final UUID PET = uid(11);
    private static final UUID APPT = uid(3);
    private static final UUID DOCTOR = uid(21);

    @Mock
    private NotificationDispatcher dispatcher;
    @Mock
    private EmailService emailService;
    @Mock
    private PeopleLookup people;

    private NotificationEventListener listener;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties("0123456789abcdef0123456789abcdef-test-secret",
                "no-reply@vetclinic.local", 50, "Asia/Ho_Chi_Minh");
        listener = new NotificationEventListener(dispatcher, emailService, people, new ProcessedEvents(), properties,
                Clock.fixed(NOW, ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    private static VisitSummary visit() {
        return new VisitSummary(VISIT_ID, "VIS-2026-7", PET, "Lu", OWNER, DOCTOR);
    }

    private static AppointmentBookedEvent appointment(String status) {
        return new AppointmentBookedEvent(UUID.randomUUID(), NOW, OWNER, APPT, "APT-20261030-00003", OWNER, PET,
                LocalDate.of(2026, 10, 31), LocalTime.of(9, 30), status);
    }

    private NotificationMessage captureRole(RoleCode role) {
        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(dispatcher).toRole(eq(role), captor.capture());
        return captor.getValue();
    }

    private NotificationMessage captureUser(UUID userId) {
        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(dispatcher).toUser(eq(userId), captor.capture());
        return captor.getValue();
    }

    private List<NotificationMessage> captureUser(UUID userId, int times) {
        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(dispatcher, times(times)).toUser(eq(userId), captor.capture());
        return captor.getAllValues();
    }

    // ================================================================== appointment.booked

    @Test
    void appointmentBooked_pendingNotifiesReceptionAndOwnerAndEmailsTheOwner() {
        when(people.requireUser(OWNER)).thenReturn(new UserSummary(OWNER, "Nguyễn Văn A", "a@example.com", null));

        listener.onAppointmentBooked(appointment("PENDING"));

        NotificationMessage forReception = captureRole(RoleCode.RECEPTIONIST);
        assertThat(forReception.type()).isEqualTo(NotificationType.APPOINTMENT_BOOKED);
        assertThat(forReception.body()).contains("APT-20261030-00003", "09:30 ngày 31/10/2026");
        assertThat(forReception.data()).containsEntry("appointmentId", APPT);
        assertThat(captureUser(OWNER).title()).isEqualTo("Đã ghi nhận lịch hẹn");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(eq("a@example.com"), contains("APT-20261030-00003"), body.capture());
        assertThat(body.getValue()).contains("Nguyễn Văn A", "09:30 ngày 31/10/2026", "chờ phòng khám xác nhận");
    }

    @Test
    void appointmentBooked_confirmedBookingByReceptionDoesNotPingReception() {
        when(people.requireUser(OWNER)).thenReturn(new UserSummary(OWNER, "Nguyễn Văn A", "a@example.com", null));

        listener.onAppointmentBooked(appointment("CONFIRMED"));

        verify(dispatcher, never()).toRole(any(), any());   // lễ tân tự đặt thì không cần báo lại cho lễ tân
        assertThat(captureUser(OWNER).title()).isEqualTo("Lịch hẹn đã được xác nhận");
        verify(emailService).send(eq("a@example.com"), anyString(), contains("Đã xác nhận"));
    }

    private static AppointmentStatusChangedEvent statusChanged(String status, boolean rescheduled, String reason) {
        return new AppointmentStatusChangedEvent(UUID.randomUUID(), NOW, uid(5), APPT, "APT-20261030-00003", OWNER, PET,
                LocalDate.of(2026, 10, 31), LocalTime.of(9, 30), status, rescheduled, reason);
    }

    @Test
    void appointmentStatusChanged_confirmedTellsTheOwnerInAppAndByEmail() {
        when(people.requireUser(OWNER)).thenReturn(new UserSummary(OWNER, "Nguyễn Văn A", "a@example.com", null));

        listener.onAppointmentStatusChanged(statusChanged("CONFIRMED", false, null));

        NotificationMessage message = captureUser(OWNER);
        assertThat(message.type()).isEqualTo(NotificationType.APPOINTMENT_UPDATED);
        assertThat(message.title()).isEqualTo("Lịch hẹn đã được xác nhận");
        assertThat(message.body()).contains("APT-20261030-00003", "09:30 ngày 31/10/2026");
        verify(emailService).send(eq("a@example.com"), contains("APT-20261030-00003"), contains("Nguyễn Văn A"));
    }

    @Test
    void appointmentStatusChanged_rejectionCarriesTheReasonAsAWarning() {
        when(people.requireUser(OWNER)).thenReturn(new UserSummary(OWNER, "Khách", "a@example.com", null));

        listener.onAppointmentStatusChanged(statusChanged("CANCELLED", false, "Phòng khám bảo trì thiết bị"));

        NotificationMessage message = captureUser(OWNER);
        assertThat(message.severity()).isEqualTo(Severity.WARNING);
        assertThat(message.body()).contains("đã bị hủy", "Phòng khám bảo trì thiết bị");
    }

    @Test
    void appointmentStatusChanged_rescheduleAndRedeliveryAreHandledOnce() {
        when(people.requireUser(OWNER)).thenReturn(new UserSummary(OWNER, "Khách", "  ", null));
        var event = statusChanged("CONFIRMED", true, "Bác sĩ đi công tác");

        listener.onAppointmentStatusChanged(event);
        listener.onAppointmentStatusChanged(event);   // RabbitMQ giao lại

        NotificationMessage message = captureUser(OWNER);   // verify(times(1)) ngầm định: không trùng
        assertThat(message.title()).isEqualTo("Phòng khám đã dời giờ hẹn");
        assertThat(message.body()).contains("Bác sĩ đi công tác");
        verifyNoInteractions(emailService);   // không có email thì chỉ thông báo trong ứng dụng
    }

    @Test
    void appointmentBooked_ownerWithoutEmailStillGetsTheInAppNotification() {
        when(people.requireUser(OWNER)).thenReturn(new UserSummary(OWNER, "Khách", "  ", null));

        listener.onAppointmentBooked(appointment("PENDING"));

        verify(dispatcher).toUser(eq(OWNER), any());
        verifyNoInteractions(emailService);
    }

    @Test
    void appointmentBooked_redeliveredEventDoesNotNotifyTwice() {
        when(people.requireUser(OWNER)).thenReturn(new UserSummary(OWNER, "Khách", "a@example.com", null));
        AppointmentBookedEvent event = appointment("PENDING");

        listener.onAppointmentBooked(event);
        listener.onAppointmentBooked(event);

        verify(dispatcher, times(1)).toUser(eq(OWNER), any());
        verify(dispatcher, times(1)).toRole(eq(RoleCode.RECEPTIONIST), any());
        verify(emailService, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void appointmentBooked_smtpFailureIsRethrownForRetry_andTheRetryOnlyRedoesTheEmail() {
        when(people.requireUser(OWNER)).thenReturn(new UserSummary(OWNER, "Khách", "a@example.com", null));
        doThrow(new AppException(ErrorCode.SERVICE_UNAVAILABLE)).doNothing().when(emailService).send(anyString(), anyString(), anyString());
        AppointmentBookedEvent event = appointment("PENDING");

        assertThatThrownBy(() -> listener.onAppointmentBooked(event)).isInstanceOf(AppException.class);
        listener.onAppointmentBooked(event);   // lần giao lại của RabbitMQ

        verify(dispatcher, times(1)).toUser(eq(OWNER), any());   // thông báo trong ứng dụng không bị lặp
        verify(emailService, times(2)).send(anyString(), anyString(), anyString());
    }

    // ================================================================== visit.checked_in

    @Test
    void checkedIn_emergencyAlertsReceptionAndDoctorsAndTellsTheOwner() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.of(visit()));

        listener.onVisitCheckedIn(new VisitCheckedInEvent(UUID.randomUUID(), NOW, uid(9), VISIT_ID, "VIS-2026-7", PET, null, true, null));

        NotificationMessage forReception = captureRole(RoleCode.RECEPTIONIST);
        assertThat(forReception.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(forReception.body()).contains("VIS-2026-7", "Lu");
        verify(dispatcher).toRole(eq(RoleCode.VETERINARIAN), any());
        assertThat(captureUser(OWNER).title()).isEqualTo("Đã tiếp nhận thú cưng");
    }

    @Test
    void checkedIn_normalVisitOnlyGoesToReception() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.of(visit()));

        listener.onVisitCheckedIn(new VisitCheckedInEvent(UUID.randomUUID(), NOW, uid(9), VISIT_ID, "VIS-2026-7", PET, APPT, false, null));

        assertThat(captureRole(RoleCode.RECEPTIONIST).severity()).isEqualTo(Severity.INFO);
        verify(dispatcher, never()).toRole(eq(RoleCode.VETERINARIAN), any());
    }

    @Test
    void checkedIn_clinicalDownStillNotifiesReceptionWithoutPetName() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.empty());

        listener.onVisitCheckedIn(new VisitCheckedInEvent(UUID.randomUUID(), NOW, uid(9), VISIT_ID, "VIS-2026-7", PET, null, false, null));

        assertThat(captureRole(RoleCode.RECEPTIONIST).body()).contains("VIS-2026-7", "thú cưng");
        verify(dispatcher, never()).toUser(any(UUID.class), any());   // không biết chủ nuôi thì không báo chủ nuôi
    }

    @Test
    void checkedIn_assignedVisitAlsoRefreshesTheDoctorsOwnQueue() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.of(visit()));

        listener.onVisitCheckedIn(new VisitCheckedInEvent(UUID.randomUUID(), NOW, uid(9), VISIT_ID, "VIS-2026-7", PET, null, false, DOCTOR));

        assertThat(captureRole(RoleCode.RECEPTIONIST).body()).contains("đã vào hàng đợi của bác sĩ");
        verify(dispatcher).toUser(eq(DOCTOR), any());   // hàng đợi riêng của bác sĩ
        verify(dispatcher).toUser(eq(OWNER), any());
    }

    // ================================================================== appointment.no_show

    @Test
    void noShow_warnsTheOwnerInAppAndByEmail() {
        when(people.requireUser(OWNER)).thenReturn(new UserSummary(OWNER, "Nguyễn Văn A", "a@example.com", null));

        listener.onAppointmentNoShow(new AppointmentNoShowEvent(UUID.randomUUID(), NOW, null, APPT, "APT-20261030-00003", OWNER, PET,
                LocalDate.of(2026, 10, 30), LocalTime.of(9, 0)));

        NotificationMessage message = captureUser(OWNER);
        assertThat(message.type()).isEqualTo(NotificationType.APPOINTMENT_NO_SHOW);
        assertThat(message.severity()).isEqualTo(Severity.WARNING);
        assertThat(message.body()).contains("APT-20261030-00003", "09:00 ngày 30/10/2026");
        verify(emailService).send(eq("a@example.com"), contains("APT-20261030-00003"), contains("Nguyễn Văn A"));
    }

    @Test
    void noShow_redeliveryDoesNotNotifyTwice() {
        when(people.requireUser(OWNER)).thenReturn(new UserSummary(OWNER, "Khách", "a@example.com", null));
        var event = new AppointmentNoShowEvent(UUID.randomUUID(), NOW, null, APPT, "APT-20261030-00003", OWNER, PET,
                LocalDate.of(2026, 10, 30), LocalTime.of(9, 0));

        listener.onAppointmentNoShow(event);
        listener.onAppointmentNoShow(event);

        verify(dispatcher, times(1)).toUser(eq(OWNER), any());
        verify(emailService, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void statusChanged_noShowIsIgnoredBecauseItHasItsOwnEvent() {
        listener.onAppointmentStatusChanged(statusChanged("NO_SHOW", false, null));

        verifyNoInteractions(dispatcher, emailService);   // nếu không chủ nuôi nhận hai thông báo
    }

    // ================================================================== visit.patient_called

    private static PatientCalledEvent called(String action, String station, UUID doctorId, String room) {
        return new PatientCalledEvent(UUID.randomUUID(), NOW, DOCTOR, action, station, VISIT_ID, "VIS-2026-7", PET, doctorId,
                uid(60), 12, room);
    }

    @Test
    void patientCalled_doctorRoomInvitesTheOwnerWithRoomAndNumber_andRefreshesTheDoctor() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.of(visit()));

        listener.onPatientCalled(called("CALLED", "DOCTOR_ROOM", DOCTOR, "P101"));

        NotificationMessage forOwner = captureUser(OWNER);
        assertThat(forOwner.type()).isEqualTo(NotificationType.PATIENT_CALLED);
        assertThat(forOwner.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(forOwner.title()).contains("phòng khám bác sĩ", "P101");
        assertThat(forOwner.body()).contains("Mời số 12", "Lu");   // gọi theo số phiếu
        assertThat(forOwner.data()).containsEntry("station", "DOCTOR_ROOM").containsEntry("roomNumber", "P101")
                .containsEntry("ticketNo", 12).containsEntry("ticketId", uid(60));
        verify(dispatcher).toUser(eq(DOCTOR), any());
    }

    @Test
    void patientCalled_labAndImagingGoToTheRightTechnicianRole() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.of(visit()));

        listener.onPatientCalled(called("CALLED", "LAB", null, "P201"));
        listener.onPatientCalled(called("CALLED", "IMAGING", null, "P202"));

        assertThat(captureUser(OWNER, 2).get(0).title()).contains("Xét nghiệm", "P201");
        assertThat(captureUser(OWNER, 2).get(1).title()).contains("Chẩn đoán hình ảnh", "P202");
        verify(dispatcher).toRole(eq(RoleCode.LAB_TECHNICIAN), any());
        verify(dispatcher).toRole(eq(RoleCode.IMAGING_TECHNICIAN), any());
    }

    @Test
    void patientCalled_clinicalDownStillRefreshesStaffButCannotReachTheOwner() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.empty());

        listener.onPatientCalled(called("CALLED", "DOCTOR_ROOM", DOCTOR, null));

        verify(dispatcher, never()).toUser(eq(OWNER), any());
        verify(dispatcher).toUser(eq(DOCTOR), any());
    }

    @Test
    void patientCalled_redeliveryDoesNotCallTwice() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.of(visit()));
        var event = called("CALLED", "DOCTOR_ROOM", DOCTOR, "P101");

        listener.onPatientCalled(event);
        listener.onPatientCalled(event);

        verify(dispatcher, times(1)).toUser(eq(OWNER), any());
    }

    // ================================================================== queue.ticket_issued

    private static QueueTicketIssuedEvent issued(String type, UUID doctorId, int priority) {
        return new QueueTicketIssuedEvent(UUID.randomUUID(), NOW, uid(5), uid(60), type, 8, VISIT_ID, "VIS-2026-7",
                PET, doctorId, priority);
    }

    @Test
    void ticketIssued_doctorQueue_givesTheOwnerTheNumber_andRefreshesTheDoctorAndReception() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.of(visit()));

        listener.onTicketIssued(issued("DOCTOR", DOCTOR, 0));

        NotificationMessage forOwner = captureUser(OWNER);
        assertThat(forOwner.type()).isEqualTo(NotificationType.TICKET_ISSUED);
        assertThat(forOwner.title()).contains("Phiếu số 8");
        assertThat(forOwner.body()).contains("Lu", "phòng khám bác sĩ");
        verify(dispatcher).toUser(eq(DOCTOR), any());
        verify(dispatcher).toRole(eq(RoleCode.RECEPTIONIST), any());
    }

    @Test
    void ticketIssued_roomQueues_refreshTheRightTechnicians_andAnEmergencyIsCritical() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.of(visit()));

        listener.onTicketIssued(issued("LABORATORY", null, 2));
        listener.onTicketIssued(issued("IMAGING", null, 0));

        ArgumentCaptor<NotificationMessage> lab = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(dispatcher).toRole(eq(RoleCode.LAB_TECHNICIAN), lab.capture());
        assertThat(lab.getValue().severity()).isEqualTo(Severity.CRITICAL);
        assertThat(lab.getValue().body()).contains("CẤP CỨU");
        verify(dispatcher).toRole(eq(RoleCode.IMAGING_TECHNICIAN), any());
        verify(dispatcher, never()).toRole(eq(RoleCode.RECEPTIONIST), any());
    }

    @Test
    void ticketIssued_redeliveryDoesNotNotifyTwice() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.of(visit()));
        var event = issued("DOCTOR", null, 0);

        listener.onTicketIssued(event);
        listener.onTicketIssued(event);

        verify(dispatcher, times(1)).toUser(eq(OWNER), any());
    }

    // ================================================================== visit.results_ready

    @Test
    void resultsReady_flagsTheRightDoctorAndTellsTheOwnerToComeBack() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.of(visit()));
        when(people.findUser(DOCTOR)).thenReturn(Optional.of(new UserSummary(DOCTOR, "BS Lan", null, "P01")));

        listener.onResultsReady(new ParaclinicalResultsReadyEvent(UUID.randomUUID(), NOW, null, VISIT_ID, "VIS-2026-7", DOCTOR, PET));

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(dispatcher).toUser(eq(DOCTOR), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(NotificationType.RESULTS_READY);
        assertThat(captor.getValue().body()).contains("VIS-2026-7", "Lu", "đầu hàng đợi");
        assertThat(captor.getValue().data()).containsEntry("visitId", VISIT_ID).containsEntry("doctorId", DOCTOR);
        assertThat(captureUser(OWNER).body()).contains("Lu", "phòng khám P01", "bác sĩ gọi số");
    }

    @Test
    void resultsReady_stillNotifiesTheDoctorWhenOtherServicesAreDown() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.empty());
        when(people.findUser(DOCTOR)).thenReturn(Optional.empty());

        listener.onResultsReady(new ParaclinicalResultsReadyEvent(UUID.randomUUID(), NOW, null, VISIT_ID, "VIS-2026-7", DOCTOR, PET));

        assertThat(captureUser(DOCTOR).body()).contains("VIS-2026-7", "thú cưng");
        verify(dispatcher, never()).toUser(eq(OWNER), any());
    }

    // ================================================================== visit.completed

    @Test
    void visitCompleted_notifiesReceptionAndOwnerAndEmailsTheElectronicReceipt() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.of(visit()));
        when(people.requireUser(OWNER)).thenReturn(new UserSummary(OWNER, "Nguyễn Văn A", "a@example.com", null));

        listener.onVisitCompleted(new VisitCompletedEvent(UUID.randomUUID(), NOW, uid(9), VISIT_ID, "VIS-2026-7", PET, false));

        NotificationMessage forReception = captureRole(RoleCode.RECEPTIONIST);
        assertThat(forReception.severity()).isEqualTo(Severity.INFO);
        assertThat(forReception.body()).contains("VIS-2026-7", "thanh toán đủ");
        assertThat(captureUser(OWNER).title()).isEqualTo("Thanh toán hoàn tất");
        verify(emailService).send(eq("a@example.com"), contains("VIS-2026-7"), contains("thu đủ viện phí dịch vụ"));
    }

    @Test
    void visitCompleted_abandonedIsADebtWarningForReceptionAndNoReceiptEmail() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.of(visit()));

        listener.onVisitCompleted(new VisitCompletedEvent(UUID.randomUUID(), NOW, uid(9), VISIT_ID, "VIS-2026-7", PET, true));

        NotificationMessage forReception = captureRole(RoleCode.RECEPTIONIST);
        assertThat(forReception.severity()).isEqualTo(Severity.WARNING);
        assertThat(forReception.title()).contains("ghi nợ");
        assertThat(captureUser(OWNER).body()).contains("chưa thanh toán");
        verifyNoInteractions(emailService);   // chưa thu đủ thì không gửi phiếu thu
    }

    @Test
    void visitCompleted_unknownOwnerSkipsTheEmailButStillNotifiesReception() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.empty());

        listener.onVisitCompleted(new VisitCompletedEvent(UUID.randomUUID(), NOW, uid(9), VISIT_ID, "VIS-2026-7", PET, false));

        verify(dispatcher).toRole(eq(RoleCode.RECEPTIONIST), any());
        verifyNoInteractions(emailService);
        verify(people, never()).requireUser(any(UUID.class));
    }

    // ================================================================== pharmacy.medicine_invoice_paid

    @Test
    void medicineInvoicePaid_tellsTheOwnerAndEmailsTheReceiptWithAmount() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.of(visit()));
        when(people.requireUser(OWNER)).thenReturn(new UserSummary(OWNER, "Nguyễn Văn A", "a@example.com", null));

        listener.onMedicineInvoicePaid(new MedicineInvoicePaidEvent(UUID.randomUUID(), NOW, uid(9), uid(4), "MINV-2026-0004", uid(3), VISIT_ID,
                new BigDecimal("157500.00")));

        NotificationMessage forOwner = captureUser(OWNER);
        assertThat(forOwner.type()).isEqualTo(NotificationType.MEDICINE_INVOICE_PAID);
        assertThat(forOwner.body()).contains("MINV-2026-0004", "Lu", "157.500đ");
        assertThat(forOwner.data()).containsEntry("invoiceCode", "MINV-2026-0004");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(eq("a@example.com"), contains("MINV-2026-0004"), body.capture());
        assertThat(body.getValue()).contains("157.500đ", "Nguyễn Văn A");
        verify(dispatcher, never()).toRole(any(), any());
    }

    @Test
    void medicineInvoicePaid_unknownOwnerSendsNothing() {
        when(people.findVisit(VISIT_ID)).thenReturn(Optional.empty());

        listener.onMedicineInvoicePaid(new MedicineInvoicePaidEvent(UUID.randomUUID(), NOW, uid(9), uid(4), "MINV-2026-0004", uid(3), VISIT_ID,
                new BigDecimal("157500.00")));

        verifyNoInteractions(dispatcher, emailService);
    }

    // ================================================================== pharmacy.low_stock

    @Test
    void lowStock_isARedAlertForAdmins() {
        listener.onLowStock(new MedicineLowStockEvent(UUID.randomUUID(), NOW, uid(9), uid(8), "MED_AMOX", "Clavamox 250mg", 8, 20));

        NotificationMessage alert = captureRole(RoleCode.ADMIN);
        assertThat(alert.type()).isEqualTo(NotificationType.LOW_STOCK);
        assertThat(alert.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(alert.body()).contains("Clavamox 250mg", "MED_AMOX", "chỉ còn 8", "ngưỡng cảnh báo 20");
        assertThat(alert.data()).containsEntry("medicineCode", "MED_AMOX").containsEntry("stockQuantity", 8);
    }

    // ================================================================== user.status_changed

    private static UserStatusChangedEvent statusChanged(String from, String to, String... roles) {
        return new UserStatusChangedEvent(UUID.randomUUID(), NOW, uid(1), DOCTOR, "Phạm Văn Mai", from, to, List.of(roles));
    }

    @Test
    void lockedDoctor_warnsReceptionistsToReassignTheirWaitingCases() {
        listener.onUserStatusChanged(statusChanged("ACTIVE", "LOCKED", "VETERINARIAN"));

        NotificationMessage alert = captureRole(RoleCode.RECEPTIONIST);
        assertThat(alert.type()).isEqualTo(NotificationType.USER_STATUS_CHANGED);
        assertThat(alert.severity()).isEqualTo(Severity.WARNING);
        assertThat(alert.body()).contains("Bác sĩ Phạm Văn Mai", "bị khóa", "phân công lại");
        assertThat(alert.data()).containsEntry("toStatus", "LOCKED").containsEntry("userId", DOCTOR);
        verify(dispatcher, never()).toRole(eq(RoleCode.ADMIN), any());
    }

    @Test
    void deactivatedDoctor_isReportedAsStoppedWorking() {
        listener.onUserStatusChanged(statusChanged("LOCKED", "INACTIVE", "VETERINARIAN", "ADMIN"));

        assertThat(captureRole(RoleCode.RECEPTIONIST).body()).contains("ngừng hoạt động");
    }

    @Test
    void reactivatedDoctor_isOnlyInformation() {
        listener.onUserStatusChanged(statusChanged("LOCKED", "ACTIVE", "VETERINARIAN"));

        NotificationMessage info = captureRole(RoleCode.RECEPTIONIST);
        assertThat(info.severity()).isEqualTo(Severity.INFO);
        assertThat(info.body()).contains("kích hoạt lại");
    }

    @Test
    void nonDoctorAccounts_doNotAffectTheQueuesSoNobodyIsNotified() {
        listener.onUserStatusChanged(statusChanged("ACTIVE", "LOCKED", "RECEPTIONIST"));

        verifyNoInteractions(dispatcher);
    }

    @Test
    void redeliveredStatusEvent_notifiesOnlyOnce() {
        UserStatusChangedEvent event = statusChanged("ACTIVE", "LOCKED", "VETERINARIAN");

        listener.onUserStatusChanged(event);
        listener.onUserStatusChanged(event);

        verify(dispatcher, times(1)).toRole(eq(RoleCode.RECEPTIONIST), any());
    }
}
