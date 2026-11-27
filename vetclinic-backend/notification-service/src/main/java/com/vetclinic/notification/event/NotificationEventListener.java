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
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.notification.config.NotificationProperties;
import com.vetclinic.notification.config.NotificationRabbitConfig;
import com.vetclinic.notification.dto.UserSummary;
import com.vetclinic.notification.dto.VisitSummary;
import com.vetclinic.notification.model.NotificationMessage;
import com.vetclinic.notification.model.NotificationType;
import com.vetclinic.notification.model.Severity;
import com.vetclinic.notification.service.EmailService;
import com.vetclinic.notification.service.NotificationDispatcher;
import com.vetclinic.notification.service.PeopleLookup;
import com.vetclinic.notification.service.ProcessedEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Biến 11 sự kiện nghiệp vụ trên RabbitMQ thành thông báo realtime (WebSocket) và email.
 * Mỗi (sự kiện, kênh) chỉ chạy thành công một lần (ProcessedEvents), nên giao lại sự kiện hoặc thử lại sau khi một bước
 * lỗi không làm người dùng nhận trùng. Lỗi thật (SMTP, auth-service sập khi cần email) thì ném để RabbitMQ thử lại 3 lần
 * rồi đưa vào DLQ. Phần làm giàu nội dung (tên thú cưng, số phòng) lỗi thì bỏ qua, thông báo vẫn đi với nội dung rút gọn.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final NotificationDispatcher dispatcher;
    private final EmailService emailService;
    private final PeopleLookup people;
    private final ProcessedEvents processed;
    private final NotificationProperties properties;
    private final Clock clock;

    // ================================================================== appointment.booked

    @RabbitListener(queues = NotificationRabbitConfig.APPOINTMENT_BOOKED_QUEUE)
    public void onAppointmentBooked(AppointmentBookedEvent event) {
        log.info("Nhận AppointmentBookedEvent: {}", event.appointmentCode());
        String when = event.startTime().format(TIME) + " ngày " + event.appointmentDate().format(DATE);
        boolean pending = "PENDING".equals(event.status());
        Map<String, Object> data = data("appointmentId", event.appointmentId(), "appointmentCode", event.appointmentCode(),
                "petId", event.petId(), "date", event.appointmentDate().toString(), "time", event.startTime().toString(),
                "status", event.status());

        processed.runOnce("push:" + event.eventId(), () -> {
            if (pending) {   // UC-17: lịch đặt online chờ Lễ tân duyệt
                dispatcher.toRole(RoleCode.RECEPTIONIST, message(NotificationType.APPOINTMENT_BOOKED, Severity.INFO,
                        "Lịch hẹn mới chờ duyệt", "Lịch " + event.appointmentCode() + " lúc " + when + " đang chờ xác nhận", data));
            }
            dispatcher.toUser(event.ownerId(), message(NotificationType.APPOINTMENT_BOOKED, Severity.INFO,
                    pending ? "Đã ghi nhận lịch hẹn" : "Lịch hẹn đã được xác nhận",
                    "Lịch " + event.appointmentCode() + " lúc " + when
                            + (pending ? " đang chờ phòng khám xác nhận" : " đã được xác nhận"), data));
        });
        processed.runOnce("mail:" + event.eventId(), () -> {
            UserSummary owner = people.requireUser(event.ownerId());
            if (isBlank(owner.email())) {
                log.info("Chủ nuôi {} chưa có email, bỏ qua thư xác nhận lịch {}", event.ownerId(), event.appointmentCode());
                return;
            }
            emailService.send(owner.email(), "[VetClinic] Xác nhận đặt lịch khám " + event.appointmentCode(),
                    "Kính gửi " + owner.fullName() + ",\n\n"
                            + "Phòng khám đã ghi nhận lịch hẹn của bạn:\n"
                            + "- Mã lịch hẹn: " + event.appointmentCode() + "\n"
                            + "- Thời gian: " + when + "\n"
                            + "- Trạng thái: " + (pending ? "Đang chờ phòng khám xác nhận (chúng tôi sẽ liên hệ lại)" : "Đã xác nhận") + "\n\n"
                            + "Vui lòng mang theo mã lịch hẹn khi đến khám. Nếu cần hủy hoặc đổi lịch, hãy thực hiện trước giờ hẹn "
                            + "ít nhất 2 tiếng.\n\nPhòng khám thú y VetClinic");
        });
    }

    // ================================================================== appointment.status_changed

    /**
     * UC-17 / FR-16: báo chủ nuôi khi phòng khám duyệt, từ chối (kèm lý do) hoặc dời giờ. Vắng mặt (NO_SHOW) đã có sự kiện riêng
     * appointment.no_show nên bỏ qua ở đây (appointment-service cũng không còn phát status_changed cho NO_SHOW).
     */
    @RabbitListener(queues = NotificationRabbitConfig.APPOINTMENT_STATUS_QUEUE)
    public void onAppointmentStatusChanged(AppointmentStatusChangedEvent event) {
        log.info("Nhận AppointmentStatusChangedEvent: {} -> {}{}", event.appointmentCode(), event.status(), event.rescheduled() ? " (dời giờ)" : "");
        String when = event.startTime().format(TIME) + " ngày " + event.appointmentDate().format(DATE);
        String reason = isBlank(event.reason()) ? null : event.reason();
        String title;
        String body;
        Severity severity = Severity.INFO;
        if (event.rescheduled()) {
            title = "Phòng khám đã dời giờ hẹn";
            body = "Lịch " + event.appointmentCode() + " được chuyển sang " + when + (reason == null ? "" : ". Lý do: " + reason);
        } else {
            switch (event.status()) {
                case "CONFIRMED" -> {
                    title = "Lịch hẹn đã được xác nhận";
                    body = "Lịch " + event.appointmentCode() + " lúc " + when + " đã được phòng khám xác nhận. Vui lòng có mặt đúng giờ.";
                }
                case "CANCELLED" -> {
                    title = "Lịch hẹn không được chấp nhận";
                    severity = Severity.WARNING;
                    body = "Lịch " + event.appointmentCode() + " lúc " + when + " đã bị hủy" + (reason == null ? "." : ". Lý do: " + reason);
                }
                default -> {
                    log.warn("Bỏ qua trạng thái {} của lịch {}", event.status(), event.appointmentCode());
                    return;
                }
            }
        }
        Map<String, Object> data = data("appointmentId", event.appointmentId(), "appointmentCode", event.appointmentCode(),
                "petId", event.petId(), "date", event.appointmentDate().toString(), "time", event.startTime().toString(),
                "status", event.status(), "rescheduled", event.rescheduled());
        String finalTitle = title;
        String finalBody = body;
        Severity finalSeverity = severity;
        processed.runOnce("push:" + event.eventId(), () ->
                dispatcher.toUser(event.ownerId(), message(NotificationType.APPOINTMENT_UPDATED, finalSeverity, finalTitle, finalBody, data)));
        processed.runOnce("mail:" + event.eventId(), () -> {
            UserSummary owner = people.requireUser(event.ownerId());
            if (isBlank(owner.email())) {
                log.info("Chủ nuôi {} chưa có email, bỏ qua thư cập nhật lịch {}", event.ownerId(), event.appointmentCode());
                return;
            }
            emailService.send(owner.email(), "[VetClinic] " + finalTitle + " - " + event.appointmentCode(),
                    "Kính gửi " + owner.fullName() + ",\n\n" + finalBody + "\n\nNếu cần hỗ trợ, vui lòng liên hệ hotline phòng khám.\n\nPhòng khám thú y VetClinic");
        });
    }

    // ================================================================== appointment.no_show

    /** EX-01: báo chủ nuôi lịch hẹn bị ghi nhận vắng mặt (hệ thống tự quét hoặc lễ tân đánh dấu). */
    @RabbitListener(queues = NotificationRabbitConfig.APPOINTMENT_NO_SHOW_QUEUE)
    public void onAppointmentNoShow(AppointmentNoShowEvent event) {
        log.info("Nhận AppointmentNoShowEvent: {}", event.appointmentCode());
        String when = event.startTime().format(TIME) + " ngày " + event.appointmentDate().format(DATE);
        String title = "Lịch hẹn được ghi nhận vắng mặt";
        String body = "Lịch " + event.appointmentCode() + " lúc " + when
                + " đã quá giờ mà chưa tiếp nhận. Vui lòng đặt lịch mới nếu vẫn cần khám.";
        Map<String, Object> data = data("appointmentId", event.appointmentId(), "appointmentCode", event.appointmentCode(),
                "petId", event.petId(), "date", event.appointmentDate().toString(), "time", event.startTime().toString(),
                "status", "NO_SHOW");
        processed.runOnce("push:" + event.eventId(), () ->
                dispatcher.toUser(event.ownerId(), message(NotificationType.APPOINTMENT_NO_SHOW, Severity.WARNING, title, body, data)));
        processed.runOnce("mail:" + event.eventId(), () -> {
            UserSummary owner = people.requireUser(event.ownerId());
            if (isBlank(owner.email())) {
                log.info("Chủ nuôi {} chưa có email, bỏ qua thư vắng mặt lịch {}", event.ownerId(), event.appointmentCode());
                return;
            }
            emailService.send(owner.email(), "[VetClinic] " + title + " - " + event.appointmentCode(),
                    "Kính gửi " + owner.fullName() + ",\n\n" + body
                            + "\n\nNếu cần hỗ trợ, vui lòng liên hệ hotline phòng khám.\n\nPhòng khám thú y VetClinic");
        });
    }

    // ================================================================== visit.checked_in

    @RabbitListener(queues = NotificationRabbitConfig.VISIT_CHECKED_IN_QUEUE)
    public void onVisitCheckedIn(VisitCheckedInEvent event) {
        log.info("Nhận VisitCheckedInEvent: {}", event.visitCode());
        processed.runOnce("push:" + event.eventId(), () -> {
            Optional<VisitSummary> visit = people.findVisit(event.visitId());
            String pet = petName(visit);
            Map<String, Object> data = data("visitId", event.visitId(), "visitCode", event.visitCode(),
                    "petId", event.petId(), "petName", pet, "emergency", event.emergency(), "doctorId", event.doctorId());

            boolean assigned = event.doctorId() != null;
            NotificationMessage queueMessage = event.emergency()
                    ? message(NotificationType.VISIT_CHECKED_IN, Severity.CRITICAL, "CA CẤP CỨU vừa tiếp nhận",
                    "Ca " + event.visitCode() + " (" + pet + ") cần khám ngay", data)
                    : message(NotificationType.VISIT_CHECKED_IN, Severity.INFO, "Ca mới vào hàng đợi",
                    "Ca " + event.visitCode() + " (" + pet + ") " + (assigned
                            ? "đã vào hàng đợi của bác sĩ" : "đang chờ phân công bác sĩ"), data);
            dispatcher.toRole(RoleCode.RECEPTIONIST, queueMessage);
            if (assigned) {   // làm mới hàng đợi riêng của đúng bác sĩ được xếp
                dispatcher.toUser(event.doctorId(), queueMessage);
            }
            if (event.emergency()) {
                dispatcher.toRole(RoleCode.VETERINARIAN, queueMessage);
            }
            visit.map(VisitSummary::ownerId).ifPresent(owner -> dispatcher.toUser(owner, message(
                    NotificationType.VISIT_CHECKED_IN, Severity.INFO, "Đã tiếp nhận thú cưng",
                    pet + " đã được tiếp nhận tại phòng khám (mã " + event.visitCode() + "). Vui lòng chờ được gọi vào phòng.", data)));
        });
    }

    // ================================================================== visit.patient_called

    /**
     * BR-34 / Q3: gọi / gọi lại (kèm số phòng và mã ca làm số thứ tự) ở phòng bác sĩ / Lab / CĐHA. Gọi số không đổi trạng thái ca.
     * Chủ nuôi nhận thông báo; nhân viên liên quan được làm mới hàng đợi.
     */
    @RabbitListener(queues = NotificationRabbitConfig.PATIENT_CALLED_QUEUE)
    public void onPatientCalled(PatientCalledEvent event) {
        log.info("Nhận PatientCalledEvent: {} {} tại {}", event.action(), event.visitCode(), event.station());
        processed.runOnce("push:" + event.eventId(), () -> {
            Optional<VisitSummary> visit = people.findVisit(event.visitId());
            String pet = petName(visit);
            String place = switch (event.station()) {
                case "LAB" -> "phòng Xét nghiệm";
                case "IMAGING" -> "phòng Chẩn đoán hình ảnh";
                default -> "phòng khám bác sĩ";
            };
            String room = isBlank(event.roomNumber()) ? place : place + " (phòng " + event.roomNumber() + ")";
            Map<String, Object> data = data("visitId", event.visitId(), "visitCode", event.visitCode(), "petId", event.petId(),
                    "petName", pet, "action", event.action(), "station", event.station(), "doctorId", event.doctorId(),
                    "ticketId", event.ticketId(), "ticketNo", event.ticketNo(), "roomNumber", event.roomNumber());
            String number = event.ticketNo() == null ? event.visitCode() : String.valueOf(event.ticketNo());

            NotificationMessage forOwner = message(NotificationType.PATIENT_CALLED, Severity.CRITICAL, "Mời vào " + room,
                    "Mời số " + number + " (" + pet + ") vào " + room + ".", data);
            visit.map(VisitSummary::ownerId).ifPresent(owner -> dispatcher.toUser(owner, forOwner));

            // Làm mới màn hình hàng đợi của người phụ trách: bác sĩ của hàng đợi, hoặc KTV của phòng Lab / CĐHA
            NotificationMessage forStaff = message(NotificationType.PATIENT_CALLED, Severity.INFO,
                    "Đã gọi số " + number, "Ca " + event.visitCode() + " (" + pet + ") - " + room, data);
            if ("LAB".equals(event.station())) {
                dispatcher.toRole(RoleCode.LAB_TECHNICIAN, forStaff);
            } else if ("IMAGING".equals(event.station())) {
                dispatcher.toRole(RoleCode.IMAGING_TECHNICIAN, forStaff);
            } else if (event.doctorId() != null) {
                dispatcher.toUser(event.doctorId(), forStaff);
            }
        });
    }

    // ================================================================== queue.ticket_issued

    /**
     * Thu tiền xong (hoặc ca cấp cứu / ca có kết quả CLS) hệ thống phát phiếu: chủ nuôi nhận số phiếu và nơi chờ; màn hình hàng đợi
     * của người phụ trách tự làm mới (Bác sĩ được chọn / Lễ tân điều phối, hoặc KTV phòng Lab / CĐHA).
     */
    @RabbitListener(queues = NotificationRabbitConfig.TICKET_ISSUED_QUEUE)
    public void onTicketIssued(QueueTicketIssuedEvent event) {
        log.info("Nhận QueueTicketIssuedEvent: phiếu {} số {} của {}", event.queueType(), event.ticketNo(), event.visitCode());
        processed.runOnce("push:" + event.eventId(), () -> {
            Optional<VisitSummary> visit = people.findVisit(event.visitId());
            String pet = petName(visit);
            String place = switch (event.queueType()) {
                case "LABORATORY" -> "phòng Xét nghiệm";
                case "IMAGING" -> "phòng Chẩn đoán hình ảnh";
                default -> "phòng khám bác sĩ";
            };
            String priority = event.priority() >= 2 ? " (CẤP CỨU)" : event.priority() == 1 ? " (có kết quả cận lâm sàng)" : "";
            Map<String, Object> data = data("ticketId", event.ticketId(), "queueType", event.queueType(), "ticketNo", event.ticketNo(),
                    "priority", event.priority(), "visitId", event.visitId(), "visitCode", event.visitCode(), "petId", event.petId(),
                    "petName", pet, "doctorId", event.doctorId());

            NotificationMessage forOwner = message(NotificationType.TICKET_ISSUED, Severity.INFO, "Phiếu số " + event.ticketNo(),
                    pet + " đã có phiếu số " + event.ticketNo() + priority + ", vui lòng chờ gọi số tại " + place + ".", data);
            visit.map(VisitSummary::ownerId).ifPresent(owner -> dispatcher.toUser(owner, forOwner));

            NotificationMessage forStaff = message(NotificationType.TICKET_ISSUED,
                    event.priority() >= 2 ? Severity.CRITICAL : Severity.INFO,
                    "Phiếu mới số " + event.ticketNo(), "Ca " + event.visitCode() + " (" + pet + ") vào hàng đợi " + place + priority, data);
            switch (event.queueType()) {
                case "LABORATORY" -> dispatcher.toRole(RoleCode.LAB_TECHNICIAN, forStaff);
                case "IMAGING" -> dispatcher.toRole(RoleCode.IMAGING_TECHNICIAN, forStaff);
                default -> {
                    if (event.doctorId() != null) {
                        dispatcher.toUser(event.doctorId(), forStaff);
                    }
                    dispatcher.toRole(RoleCode.RECEPTIONIST, forStaff);   // bảng điều phối: ca rời mục "chờ thu phí", vào hàng đợi
                }
            }
        });
    }

    // ================================================================== visit.results_ready

    /** T06 / Q5: ca đã đủ kết quả cận lâm sàng, tự lên đầu hàng đợi của bác sĩ khám chính (không còn "Khóa ưu tiên"). */
    @RabbitListener(queues = NotificationRabbitConfig.RESULTS_READY_QUEUE)
    public void onResultsReady(ParaclinicalResultsReadyEvent event) {
        log.info("Nhận ParaclinicalResultsReadyEvent: ca {} -> bác sĩ {}", event.visitCode(), event.doctorId());
        processed.runOnce("push:" + event.eventId(), () -> {
            Optional<VisitSummary> visit = people.findVisit(event.visitId());
            Optional<UserSummary> doctor = event.doctorId() == null ? Optional.empty() : people.findUser(event.doctorId());
            String pet = petName(visit);
            String room = doctor.map(UserSummary::roomNumber).filter(r -> !isBlank(r)).orElse("của bác sĩ");
            Map<String, Object> data = data("visitId", event.visitId(), "visitCode", event.visitCode(),
                    "petId", event.petId(), "petName", pet, "doctorId", event.doctorId());

            if (event.doctorId() != null) {   // huy hiệu "Có kết quả" + làm mới hàng đợi bác sĩ
                dispatcher.toUser(event.doctorId(), message(NotificationType.RESULTS_READY, Severity.WARNING,
                        "Có ca đã đủ kết quả cận lâm sàng",
                        "Ca " + event.visitCode() + " (" + pet + ") đã có kết quả và đang ở đầu hàng đợi của bạn", data));
            }
            visit.map(VisitSummary::ownerId).ifPresent(owner -> dispatcher.toUser(owner, message(
                    NotificationType.RESULTS_READY, Severity.INFO, "Đã có kết quả cận lâm sàng",
                    "Kết quả của " + pet + " đã sẵn sàng. Vui lòng quay lại phòng khám " + room
                            + " và chờ bác sĩ gọi số.", data)));
        });
    }

    // ================================================================== visit.completed

    /** Ca COMPLETED (thu đủ hóa đơn dịch vụ, hoặc khách bỏ về): phiếu thu điện tử cho chủ nuôi. Chỉ để thông báo. */
    @RabbitListener(queues = NotificationRabbitConfig.VISIT_COMPLETED_QUEUE)
    public void onVisitCompleted(VisitCompletedEvent event) {
        log.info("Nhận VisitCompletedEvent: {} (khách bỏ về: {})", event.visitCode(), event.abandoned());
        Optional<VisitSummary> visit = people.findVisit(event.visitId());
        String pet = petName(visit);
        Map<String, Object> data = data("visitId", event.visitId(), "visitCode", event.visitCode(),
                "petId", event.petId(), "petName", pet, "abandoned", event.abandoned());

        processed.runOnce("push:" + event.eventId(), () -> {
            if (event.abandoned()) {
                dispatcher.toRole(RoleCode.RECEPTIONIST, message(NotificationType.VISIT_COMPLETED, Severity.WARNING,
                        "Khách bỏ về, ghi nợ viện phí",
                        "Ca " + event.visitCode() + " (" + pet + ") đã đóng; hóa đơn dịch vụ chưa thu được giữ làm công nợ", data));
            } else {
                dispatcher.toRole(RoleCode.RECEPTIONIST, message(NotificationType.VISIT_COMPLETED, Severity.INFO,
                        "Ca khám đã hoàn tất", "Ca " + event.visitCode() + " (" + pet + ") đã thanh toán đủ", data));
            }
            visit.map(VisitSummary::ownerId).ifPresent(owner -> dispatcher.toUser(owner, message(
                    NotificationType.VISIT_COMPLETED, Severity.INFO,
                    event.abandoned() ? "Ca khám đã được đóng" : "Thanh toán hoàn tất",
                    event.abandoned()
                            ? "Ca khám " + event.visitCode() + " của " + pet
                            + " đã đóng. Phần viện phí chưa thanh toán sẽ được thu khi bạn quay lại phòng khám."
                            : "Cảm ơn bạn. Ca khám " + event.visitCode() + " của " + pet + " đã hoàn tất, bệnh án đã được cập nhật.",
                    data)));
        });
        if (!event.abandoned()) {   // phiếu thu điện tử chỉ gửi khi đã thu đủ
            processed.runOnce("mail:" + event.eventId(), () -> {
                Optional<UUID> ownerId = visit.map(VisitSummary::ownerId);
                if (ownerId.isEmpty()) {
                    return;   // không biết chủ nuôi (clinical tạm không phản hồi): bỏ thư, thông báo trong ứng dụng đã đi
                }
                UserSummary owner = people.requireUser(ownerId.get());
                if (isBlank(owner.email())) {
                    return;
                }
                emailService.send(owner.email(), "[VetClinic] Phiếu thu điện tử ca khám " + event.visitCode(),
                        "Kính gửi " + owner.fullName() + ",\n\n"
                                + "Phòng khám xác nhận đã thu đủ viện phí dịch vụ cho ca khám " + event.visitCode()
                                + " của " + pet + ".\nBệnh án của " + pet + " đã được cập nhật trên cổng thông tin.\n"
                                + "\nCảm ơn bạn đã tin tưởng phòng khám thú y VetClinic.");
            });
        }
    }

    // ================================================================== pharmacy.medicine_invoice_paid

    /** Hóa đơn thuốc đã PAID (kho đã trừ). Không liên quan trạng thái đợt khám. */
    @RabbitListener(queues = NotificationRabbitConfig.MEDICINE_INVOICE_PAID_QUEUE)
    public void onMedicineInvoicePaid(MedicineInvoicePaidEvent event) {
        log.info("Nhận MedicineInvoicePaidEvent: {}", event.invoiceCode());
        Optional<VisitSummary> visit = people.findVisit(event.visitId());
        String pet = petName(visit);
        String amount = event.payableAmount() == null ? null
                : String.format(Locale.forLanguageTag("vi-VN"), "%,.0f", event.payableAmount()) + "đ";
        Map<String, Object> data = data("invoiceId", event.invoiceId(), "invoiceCode", event.invoiceCode(),
                "prescriptionId", event.prescriptionId(), "visitId", event.visitId(), "petName", pet,
                "payableAmount", event.payableAmount());

        processed.runOnce("push:" + event.eventId(), () ->
                visit.map(VisitSummary::ownerId).ifPresent(owner -> dispatcher.toUser(owner, message(
                        NotificationType.MEDICINE_INVOICE_PAID, Severity.INFO, "Đã thanh toán thuốc",
                        "Hóa đơn thuốc " + event.invoiceCode() + " của " + pet + (amount == null ? "" : " (" + amount + ")")
                                + " đã thanh toán. Vui lòng dùng thuốc đúng hướng dẫn của bác sĩ.", data))));
        processed.runOnce("mail:" + event.eventId(), () -> {
            Optional<UUID> ownerId = visit.map(VisitSummary::ownerId);
            if (ownerId.isEmpty()) {
                return;
            }
            UserSummary owner = people.requireUser(ownerId.get());
            if (isBlank(owner.email())) {
                return;
            }
            emailService.send(owner.email(), "[VetClinic] Phiếu thu thuốc " + event.invoiceCode(),
                    "Kính gửi " + owner.fullName() + ",\n\n"
                            + "Phòng khám xác nhận đã thu tiền thuốc cho " + pet + ":\n"
                            + "- Mã hóa đơn thuốc: " + event.invoiceCode() + "\n"
                            + (amount == null ? "" : "- Số tiền: " + amount + "\n")
                            + "\nVui lòng dùng thuốc đúng hướng dẫn của bác sĩ.\n\nCảm ơn bạn đã tin tưởng phòng khám thú y VetClinic.");
        });
    }

    // ================================================================== pharmacy.low_stock

    @RabbitListener(queues = NotificationRabbitConfig.LOW_STOCK_QUEUE)
    public void onLowStock(MedicineLowStockEvent event) {
        log.info("Nhận MedicineLowStockEvent: {} còn {}", event.medicineCode(), event.stockQuantity());
        processed.runOnce("push:" + event.eventId(), () -> dispatcher.toRole(RoleCode.ADMIN, message(
                NotificationType.LOW_STOCK, Severity.CRITICAL, "Cảnh báo tồn kho thấp",
                event.medicineName() + " (" + event.medicineCode() + ") chỉ còn " + event.stockQuantity()
                        + ", ngưỡng cảnh báo " + event.minStockAlert() + ". Cần nhập thêm.",
                data("medicineId", event.medicineId(), "medicineCode", event.medicineCode(),
                        "stockQuantity", event.stockQuantity(), "minStockAlert", event.minStockAlert()))));
    }

    // ================================================================== user.status_changed

    /**
     * Bác sĩ bị khóa / ngừng sử dụng thì Lễ tân phải phân công lại các phiếu đang chờ của bác sĩ đó; kích hoạt lại thì chỉ báo tin.
     * Tài khoản không phải Bác sĩ không ảnh hưởng hàng đợi nên không báo.
     */
    @RabbitListener(queues = NotificationRabbitConfig.USER_STATUS_CHANGED_QUEUE)
    public void onUserStatusChanged(UserStatusChangedEvent event) {
        log.info("Nhận UserStatusChangedEvent: {} {} -> {}", event.userId(), event.fromStatus(), event.toStatus());
        if (event.roles() == null || !event.roles().contains(RoleCode.VETERINARIAN.name())) {
            return;
        }
        boolean reactivated = "ACTIVE".equals(event.toStatus());
        String name = isBlank(event.fullName()) ? "Bác sĩ" : "Bác sĩ " + event.fullName();
        String title = reactivated ? "Bác sĩ hoạt động trở lại" : "Bác sĩ không còn nhận ca";
        String body = reactivated
                ? name + " đã được kích hoạt lại và có thể nhận ca."
                : name + " đã bị " + ("INACTIVE".equals(event.toStatus()) ? "ngừng hoạt động" : "khóa")
                        + ". Hãy phân công lại các ca đang chờ của bác sĩ này.";
        processed.runOnce("push:" + event.eventId(), () -> dispatcher.toRole(RoleCode.RECEPTIONIST, message(
                NotificationType.USER_STATUS_CHANGED, reactivated ? Severity.INFO : Severity.WARNING, title, body,
                data("userId", event.userId(), "fullName", event.fullName(),
                        "fromStatus", event.fromStatus(), "toStatus", event.toStatus()))));
    }

    // ================================================================== helper

    private NotificationMessage message(NotificationType type, Severity severity, String title, String body,
                                        Map<String, Object> data) {
        return NotificationMessage.of(type, severity, title, body, data, clock.instant());
    }

    private static String petName(Optional<VisitSummary> visit) {
        return visit.map(VisitSummary::petName).filter(n -> !isBlank(n)).orElse("thú cưng");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Dựng map giữ thứ tự; chấp nhận giá trị null (Map.of không cho). */
    private static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
