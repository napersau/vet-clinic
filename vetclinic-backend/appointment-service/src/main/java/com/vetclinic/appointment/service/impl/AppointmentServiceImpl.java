package com.vetclinic.appointment.service.impl;

import com.vetclinic.appointment.client.AuthClient;
import com.vetclinic.appointment.client.PetClient;
import com.vetclinic.appointment.config.BookingProperties;
import com.vetclinic.appointment.constant.AppointmentStatus;
import com.vetclinic.appointment.constant.BookingChannel;
import com.vetclinic.appointment.dto.request.CancelAppointmentRequest;
import com.vetclinic.appointment.dto.request.CreateAppointmentRequest;
import com.vetclinic.appointment.dto.request.RescheduleAppointmentRequest;
import com.vetclinic.appointment.dto.response.AppointmentHistoryResponse;
import com.vetclinic.appointment.dto.response.AppointmentResponse;
import com.vetclinic.appointment.dto.response.PetSummaryResponse;
import com.vetclinic.appointment.dto.response.UserSummaryResponse;
import com.vetclinic.appointment.entity.Appointment;
import com.vetclinic.appointment.entity.AppointmentStatusHistory;
import com.vetclinic.appointment.mapper.AppointmentMapper;
import com.vetclinic.appointment.repository.AppointmentRepository;
import com.vetclinic.appointment.repository.AppointmentSpecifications;
import com.vetclinic.appointment.repository.AppointmentStatusHistoryRepository;
import com.vetclinic.appointment.service.AppointmentFilter;
import com.vetclinic.appointment.service.AppointmentService;
import com.vetclinic.appointment.service.SlotLockService;
import com.vetclinic.appointment.service.SlotPolicy;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.dto.PageResponse;
import com.vetclinic.common.event.AppointmentBookedEvent;
import com.vetclinic.common.event.AppointmentNoShowEvent;
import com.vetclinic.common.event.AppointmentStatusChangedEvent;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityUtils;
import com.vetclinic.common.util.Codes;
import com.vetclinic.common.util.UuidV7;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class AppointmentServiceImpl implements AppointmentService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int CODE_ATTEMPTS = 8;
    private static final String AGGREGATE = "APPOINTMENT";

    private final AppointmentRepository appointmentRepository;
    private final AppointmentStatusHistoryRepository historyRepository;
    private final AuthClient authClient;
    private final PetClient petClient;
    private final SlotPolicy slotPolicy;
    private final SlotLockService slotLockService;
    private final TransactionTemplate transactionTemplate;
    private final OutboxWriter outboxWriter;
    private final AppointmentMapper mapper;
    private final BookingProperties properties;
    private final Clock clock;

    public AppointmentServiceImpl(AppointmentRepository appointmentRepository,
                                  AppointmentStatusHistoryRepository historyRepository,
                                  AuthClient authClient,
                                  PetClient petClient,
                                  SlotPolicy slotPolicy,
                                  SlotLockService slotLockService,
                                  PlatformTransactionManager transactionManager,
                                  OutboxWriter outboxWriter,
                                  AppointmentMapper mapper,
                                  BookingProperties properties,
                                  Clock clock) {
        this.appointmentRepository = appointmentRepository;
        this.historyRepository = historyRepository;
        this.authClient = authClient;
        this.petClient = petClient;
        this.slotPolicy = slotPolicy;
        this.slotLockService = slotLockService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.outboxWriter = outboxWriter;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    private record Actor(UUID id, RoleCode role) {
    }

    private record Booking(UUID ownerId, BookingChannel channel, AppointmentStatus status) {
    }

    // ================================================================== tạo lịch hẹn (UC-14, UC-16)

    /**
     * KHÔNG đánh dấu @Transactional: các lời gọi OpenFeign phải nằm ngoài transaction (quy chuẩn §9.1), và khóa
     * khung giờ phải còn giữ SAU KHI transaction commit. Thứ tự: kiểm tra đầu vào -> Feign (thú cưng + chủ nuôi) -> lấy khóa
     * -> [transaction: đếm chỗ + chèn] -> nhả khóa.
     */
    @Override
    public AppointmentResponse create(CreateAppointmentRequest request) {
        Actor actor = currentActor();
        LocalDate date = request.appointmentDate();
        LocalTime start = request.startTime();
        slotPolicy.assertBookable(date, start);

        Booking booking = resolveBooking(actor, request);
        // Thú cưng phải tồn tại và thuộc đúng chủ nuôi; tên thú cưng + tên / SĐT chủ nuôi được CHỤP lên lịch hẹn (snapshot)
        PetSummaryResponse pet = loadPet(request.petId(), booking.ownerId());

        int capacity = slotPolicy.activeDoctorCapacity();
        if (capacity <= 0) {
            throw new AppException(ErrorCode.SLOT_CAPACITY_EXCEEDED,
                    "Hiện chưa có bác sĩ nào đang hoạt động nên chưa nhận đặt lịch");
        }

        List<String> locks = List.of(SlotLockService.slotLock(date, start),
                SlotLockService.petDayLock(request.petId(), date));
        Appointment saved = slotLockService.runLocked(locks,
                () -> transactionTemplate.execute(tx -> insertAppointment(actor, request, booking, pet, capacity)));
        return toResponse(saved);
    }

    private Booking resolveBooking(Actor actor, CreateAppointmentRequest request) {
        return switch (actor.role()) {
            // Chủ nuôi: ownerId lấy từ token, không bao giờ tin vào body
            case PET_OWNER -> new Booking(actor.id(), BookingChannel.ONLINE, AppointmentStatus.PENDING);
            case RECEPTIONIST -> {
                if (request.ownerId() == null) {
                    throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Lễ tân phải chọn chủ thú cưng (ownerId)");
                }
                if (request.bookingChannel() == null || request.bookingChannel() == BookingChannel.ONLINE) {
                    throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                            "Kênh đặt lịch của lễ tân phải là PHONE hoặc WALK_IN");
                }
                assertActivePetOwner(request.ownerId());
                yield new Booking(request.ownerId(), request.bookingChannel(), AppointmentStatus.CONFIRMED);
            }
            default -> throw new AppException(ErrorCode.ACCESS_DENIED);
        };
    }

    /** Chạy trong transaction + khóa khung giờ: đếm chỗ và chèn là một bước không bị chen ngang. */
    private Appointment insertAppointment(Actor actor, CreateAppointmentRequest request, Booking booking,
                                          PetSummaryResponse pet, int capacity) {
        LocalDate date = request.appointmentDate();
        LocalTime start = request.startTime();

        long booked = appointmentRepository.countByAppointmentDateAndStartTimeAndStatusIn(
                date, start, AppointmentStatus.OCCUPYING);
        if (booked >= capacity) {
            throw new AppException(ErrorCode.SLOT_CAPACITY_EXCEEDED);
        }
        if (appointmentRepository.existsByPetIdAndAppointmentDateAndStatusIn(
                request.petId(), date, AppointmentStatus.OCCUPYING)) {
            throw new AppException(ErrorCode.DUPLICATE_APPOINTMENT);
        }

        Instant now = clock.instant();
        boolean confirmed = booking.status() == AppointmentStatus.CONFIRMED;
        Appointment appointment = Appointment.builder()
                .appointmentCode(newCode())
                .petId(request.petId())
                .ownerId(booking.ownerId())
                .petNameSnapshot(pet.name())
                .ownerNameSnapshot(pet.ownerName())
                .ownerPhoneSnapshot(pet.ownerPhone())
                .appointmentDate(date)
                .startTime(start)
                .reason(request.reason().trim())
                .bookingChannel(booking.channel())
                .status(booking.status())
                .build();
        appointment = appointmentRepository.saveAndFlush(appointment);

        recordHistory(appointment.getId(), null, booking.status(), actor.id(),
                confirmed ? "Lễ tân tạo lịch hẹn qua kênh " + booking.channel()
                        : "Chủ nuôi gửi yêu cầu đặt lịch qua cổng trực tuyến");

        // Transactional Outbox: ghi sự kiện cùng giao dịch; OutboxRelay gửi sang RabbitMQ sau khi commit (RabbitMQ tắt thì vẫn không mất)
        outboxWriter.write(AGGREGATE, appointment.getId(), new AppointmentBookedEvent(UuidV7.generate(clock), now, actor.id(),
                appointment.getId(), appointment.getAppointmentCode(), booking.ownerId(),
                appointment.getPetId(), date, start, appointment.getStatus().name()));
        return appointment;
    }

    // ================================================================== tra cứu

    /** Chỉ đọc bảng của chính service này (owner_id + snapshot): không còn gọi Feign sang clinical / auth. */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<AppointmentResponse> search(AppointmentFilter filter) {
        Actor actor = currentActor();
        // Chủ nuôi chỉ thấy lịch hẹn của chính mình, bất kể tham số gửi lên
        UUID ownerId = actor.role() == RoleCode.PET_OWNER ? actor.id() : filter.ownerId();

        Sort.Direction direction = filter.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC;
        // UUIDv7 xếp được theo thời gian nên dùng làm khóa phụ ổn định
        Sort sort = "createdAt".equalsIgnoreCase(filter.sortBy())
                ? Sort.by(direction, "createdAt", "id")
                : Sort.by(direction, "appointmentDate", "startTime", "id");
        PageRequest pageable = PageRequest.of(Math.max(filter.page(), 0),
                Math.min(Math.max(filter.size(), 1), MAX_PAGE_SIZE), sort);

        Page<Appointment> result = appointmentRepository.findAll(AppointmentSpecifications.filter(
                ownerId, filter.petId(), filter.code(), filter.fromDate(), filter.toDate(), filter.status()), pageable);
        return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isLast(), this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AppointmentResponse getById(UUID id) {
        Actor actor = currentActor();
        Appointment appointment = findById(id);
        assertVisibleTo(actor, appointment);   // chủ nuôi chỉ xem được lịch của mình
        return toResponse(appointment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentHistoryResponse> getHistory(UUID id) {
        findById(id);
        return historyRepository.findByAppointmentIdOrderByIdAsc(id).stream()
                .map(mapper::toHistoryResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AppointmentResponse getForInternal(UUID id) {
        return toResponse(findById(id));
    }

    // ================================================================== chuyển trạng thái (UC-15, UC-17)
    //
    // Từ v4.0 lịch hẹn có sẵn owner_id nên không còn phải tra chủ nuôi qua clinical-service: các thao tác chỉ ghi dữ liệu của chính service này
    // (khóa dòng + khóa lạc quan @Version) và chạy trọn trong một @Transactional.

    @Override
    @Transactional
    public AppointmentResponse confirm(UUID id) {
        Actor actor = currentActor();
        requireRole(actor, RoleCode.RECEPTIONIST);
        Appointment appointment = lockForUpdate(id);
        if (appointment.getStatus() != AppointmentStatus.PENDING) {
            throw new AppException(ErrorCode.INVALID_APPOINTMENT_STATUS,
                    "Chỉ lịch hẹn đang chờ xác nhận (PENDING) mới được duyệt");
        }
        // Người duyệt và thời điểm duyệt nằm ở dòng lịch sử (changed_by_user_id, created_at)
        transition(appointment, AppointmentStatus.CONFIRMED, actor.id(), "Lễ tân xác nhận lịch hẹn", null);
        return toResponse(appointment);
    }

    @Override
    @Transactional
    public AppointmentResponse cancel(UUID id, CancelAppointmentRequest request) {
        Actor actor = currentActor();
        if (actor.role() != RoleCode.PET_OWNER && actor.role() != RoleCode.RECEPTIONIST) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        Appointment appointment = lockForUpdate(id);
        assertVisibleTo(actor, appointment);
        if (!AppointmentStatus.MODIFIABLE.contains(appointment.getStatus())) {
            throw new AppException(ErrorCode.INVALID_APPOINTMENT_STATUS,
                    "Chỉ lịch hẹn đang chờ xác nhận hoặc đã xác nhận mới hủy được");
        }
        if (actor.role() == RoleCode.PET_OWNER) {
            assertOwnerTimeWindow(appointment, "hủy");
        }
        // Lý do hủy ghi nguyên văn vào appointment_status_histories.notes; ai hủy = changed_by_user_id
        String reason = request.reason().trim();
        transition(appointment, AppointmentStatus.CANCELLED, actor.id(), reason, reason);
        return toResponse(appointment);
    }

    /**
     * Không đánh dấu @Transactional vì lý do giống create (khóa khung giờ phải còn giữ sau khi transaction commit).
     */
    @Override
    public AppointmentResponse reschedule(UUID id, RescheduleAppointmentRequest request) {
        Actor actor = currentActor();
        if (actor.role() != RoleCode.PET_OWNER && actor.role() != RoleCode.RECEPTIONIST) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        LocalDate newDate = request.newDate();
        LocalTime newStart = request.newStartTime();

        // Đọc nhanh (không khóa) để biết thú cưng và kiểm tra quyền; điều kiện thật được kiểm tra lại trong transaction
        Appointment current = findById(id);
        assertVisibleTo(actor, current);
        slotPolicy.assertBookable(newDate, newStart);

        int capacity = slotPolicy.activeDoctorCapacity();
        if (capacity <= 0) {
            throw new AppException(ErrorCode.SLOT_CAPACITY_EXCEEDED,
                    "Hiện chưa có bác sĩ nào đang hoạt động nên chưa nhận đặt lịch");
        }

        List<String> locks = List.of(SlotLockService.slotLock(newDate, newStart),
                SlotLockService.petDayLock(current.getPetId(), newDate));
        Appointment saved = slotLockService.runLocked(locks,
                () -> transactionTemplate.execute(tx -> applyReschedule(actor, id, request, capacity)));
        return toResponse(saved);
    }

    private Appointment applyReschedule(Actor actor, UUID id, RescheduleAppointmentRequest request, int capacity) {
        LocalDate newDate = request.newDate();
        LocalTime newStart = request.newStartTime();

        Appointment appointment = lockForUpdate(id);
        if (!AppointmentStatus.MODIFIABLE.contains(appointment.getStatus())) {
            throw new AppException(ErrorCode.INVALID_APPOINTMENT_STATUS,
                    "Chỉ lịch hẹn đang chờ xác nhận hoặc đã xác nhận mới đổi được");
        }
        if (actor.role() == RoleCode.PET_OWNER) {
            assertOwnerTimeWindow(appointment, "đổi");
        }
        if (newDate.equals(appointment.getAppointmentDate()) && newStart.equals(appointment.getStartTime())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Khung giờ mới trùng với khung giờ hiện tại");
        }

        long booked = appointmentRepository.countByAppointmentDateAndStartTimeAndStatusIn(
                newDate, newStart, AppointmentStatus.OCCUPYING);
        if (booked >= capacity) {
            throw new AppException(ErrorCode.SLOT_CAPACITY_EXCEEDED);
        }
        if (appointmentRepository.existsByPetIdAndAppointmentDateAndStatusInAndIdNot(
                appointment.getPetId(), newDate, AppointmentStatus.OCCUPYING, appointment.getId())) {
            throw new AppException(ErrorCode.DUPLICATE_APPOINTMENT);
        }

        String note = "Đổi lịch từ " + appointment.getAppointmentDate() + " " + appointment.getStartTime()
                + " sang " + newDate + " " + newStart
                + (request.reason() == null || request.reason().isBlank() ? "" : ". Lý do: " + request.reason().trim());
        appointment.setAppointmentDate(newDate);
        appointment.setStartTime(newStart);
        appointmentRepository.save(appointment);
        // Đổi lịch không đổi trạng thái: from = to, chi tiết ở notes
        recordHistory(appointment.getId(), appointment.getStatus(), appointment.getStatus(), actor.id(), note);
        // Lễ tân dời giờ hộ thì chủ nuôi cần được báo; chủ nuôi tự đổi thì không
        if (actor.role() != RoleCode.PET_OWNER) {
            notifyOwner(appointment, actor.id(), true, request.reason() == null ? null : request.reason().trim());
        }
        return appointment;
    }

    // ================================================================== vắng mặt (EX-01) và check-in

    @Override
    @Transactional
    public AppointmentResponse markNoShow(UUID id) {
        Actor actor = currentActor();
        requireRole(actor, RoleCode.RECEPTIONIST);
        Appointment appointment = lockForUpdate(id);
        if (appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new AppException(ErrorCode.INVALID_APPOINTMENT_STATUS,
                    "Chỉ lịch hẹn đã xác nhận (CONFIRMED) mới đánh dấu vắng mặt được");
        }
        if (!isNoShowDue(appointment)) {
            throw new AppException(ErrorCode.INVALID_APPOINTMENT_STATUS,
                    "Chưa quá " + properties.noShowGraceMinutes() + " phút sau giờ hẹn nên chưa thể đánh dấu vắng mặt");
        }
        transition(appointment, AppointmentStatus.NO_SHOW, actor.id(), "Lễ tân xác nhận khách vắng mặt (No-show)", null);
        return toResponse(appointment);
    }

    @Override
    @Transactional
    public boolean markNoShowBySystem(UUID id) {
        Appointment appointment = appointmentRepository.findByIdForUpdate(id).orElse(null);
        // Idempotent: khách vừa check-in hoặc lịch vừa bị hủy trong lúc tiến trình quét đang chạy thì bỏ qua
        if (appointment == null || appointment.getStatus() != AppointmentStatus.CONFIRMED || !isNoShowDue(appointment)) {
            return false;
        }
        transition(appointment, AppointmentStatus.NO_SHOW, null,
                "Hệ thống tự động chuyển NO_SHOW do quá " + properties.noShowGraceMinutes()
                        + " phút sau giờ hẹn mà khách chưa làm thủ tục tiếp đón.", null);
        return true;
    }

    @Override
    @Transactional
    public boolean expirePendingBySystem(UUID id) {
        Appointment appointment = appointmentRepository.findByIdForUpdate(id).orElse(null);
        // Idempotent: lễ tân vừa duyệt hoặc chủ nuôi vừa hủy trong lúc tiến trình quét chạy thì bỏ qua
        if (appointment == null || appointment.getStatus() != AppointmentStatus.PENDING || !isPendingExpired(appointment)) {
            return false;
        }
        // changed_by_user_id = null: hệ thống tự hủy
        String reason = "Quá giờ hẹn mà phòng khám chưa xác nhận (hệ thống tự động hủy)";
        transition(appointment, AppointmentStatus.CANCELLED, null, reason, reason);
        return true;
    }

    @Override
    @Transactional
    public void markCheckedIn(UUID appointmentId, UUID actorUserId, String visitCode) {
        // Không tìm thấy là lỗi vĩnh viễn: ném lỗi để message sau khi hết retry được đẩy sang DLQ cho người xem xét
        Appointment appointment = lockForUpdate(appointmentId);
        switch (appointment.getStatus()) {
            case CHECKED_IN -> log.debug("Lịch hẹn {} đã CHECKED_IN, bỏ qua sự kiện trùng", appointmentId);
            case CONFIRMED -> transition(appointment, AppointmentStatus.CHECKED_IN, actorUserId,
                    "Khách đã tiếp nhận tại quầy, đợt khám " + visitCode, null);
            // FR-18: khách đến trễ sau khi đã bị NO_SHOW được tiếp nhận như khách vãng lai; lịch GIỮ NGUYÊN NO_SHOW (ghi nhận lịch sử uy tín)
            case NO_SHOW -> log.warn("Lịch hẹn {} đã NO_SHOW nhưng khách vẫn được tiếp nhận (đợt khám {}): giữ nguyên NO_SHOW (FR-18)",
                    appointmentId, visitCode);
            default -> log.warn("Bỏ qua sự kiện check-in của lịch hẹn {} đang ở trạng thái {} (đợt khám {})",
                    appointmentId, appointment.getStatus(), visitCode);
        }
    }

    // ================================================================== snapshot (consumer sự kiện)

    @Override
    @Transactional
    public int refreshOwnerSnapshot(UUID ownerId, String ownerName, String ownerPhone) {
        if (ownerId == null || ownerName == null || ownerPhone == null) {
            return 0;
        }
        return appointmentRepository.updateOwnerSnapshot(ownerId, ownerName, ownerPhone);
    }

    @Override
    @Transactional
    public int refreshPetSnapshot(UUID petId, String petName, UUID ownerId, String ownerName, String ownerPhone) {
        if (petId == null) {
            return 0;
        }
        int updated = petName == null ? 0 : appointmentRepository.updatePetNameSnapshot(petId, petName);
        if (ownerId != null && ownerName != null && ownerPhone != null) {
            // Thú cưng chuyển sang chủ khác: lịch còn hiệu lực đi theo chủ mới
            updated += appointmentRepository.transferOwner(petId, ownerId, ownerName, ownerPhone, AppointmentStatus.MODIFIABLE);
        }
        return updated;
    }

    // ================================================================== helper

    /** Thời điểm kết thúc khung hẹn = giờ bắt đầu + độ dài khung (appointments không có cột end_time). */
    private LocalDateTime endOf(Appointment appointment) {
        return LocalDateTime.of(appointment.getAppointmentDate(), slotPolicy.endOf(appointment.getStartTime()));
    }

    /**
     * EX-01 / FR-17: lịch CONFIRMED quá {@code appointment_date + start_time + 30 phút} mà chưa check-in là vắng mặt. Mốc tính từ giờ
     * BẮT ĐẦU khung (không phải giờ kết thúc khung như v2).
     */
    private boolean isNoShowDue(Appointment appointment) {
        LocalDateTime start = LocalDateTime.of(appointment.getAppointmentDate(), appointment.getStartTime());
        return slotPolicy.now().isAfter(start.plusMinutes(properties.noShowGraceMinutes()));
    }

    private boolean isPendingExpired(Appointment appointment) {
        return slotPolicy.now().isAfter(endOf(appointment));
    }

    /** Hình dạng response vẫn có endTime: suy ra từ giờ bắt đầu (các trường kiểm toán do AppointmentEnricher gắn). */
    private AppointmentResponse toResponse(Appointment appointment) {
        return mapper.toResponse(appointment).withEndTime(slotPolicy.endOf(appointment.getStartTime()));
    }

    /** Chủ nuôi chỉ tự hủy / đổi được trước giờ hẹn tối thiểu N giờ; lễ tân không bị giới hạn này. */
    private void assertOwnerTimeWindow(Appointment appointment, String verb) {
        LocalDateTime start = LocalDateTime.of(appointment.getAppointmentDate(), appointment.getStartTime());
        if (slotPolicy.now().isAfter(start.minusHours(properties.minCancelHoursBefore()))) {
            throw new AppException(ErrorCode.CANNOT_CANCEL_APPOINTMENT,
                    "Đã quá thời gian cho phép tự " + verb + " lịch trên hệ thống (tối thiểu "
                            + properties.minCancelHoursBefore() + " tiếng trước giờ hẹn). "
                            + "Vui lòng liên hệ trực tiếp hotline phòng khám để được hỗ trợ");
        }
    }

    /** Chủ nuôi chỉ thao tác được trên lịch của mình; của người khác trả "không tìm thấy" để không lộ sự tồn tại. */
    private void assertVisibleTo(Actor actor, Appointment appointment) {
        if (actor.role() == RoleCode.PET_OWNER && !actor.id().equals(appointment.getOwnerId())) {
            throw new AppException(ErrorCode.APPOINTMENT_NOT_FOUND);
        }
    }

    /**
     * Đổi trạng thái + ghi lịch sử (người thao tác, lý do ở notes) + báo chủ nuôi.
     *
     * @param note   ghi chú ghi vào lịch sử (với CANCELLED: chính là lý do hủy)
     * @param reason lý do gửi kèm thông báo cho chủ nuôi; có thể null
     */
    private void transition(Appointment appointment, AppointmentStatus to, UUID actorId, String note, String reason) {
        AppointmentStatus from = appointment.getStatus();
        appointment.setStatus(to);
        appointmentRepository.save(appointment);
        recordHistory(appointment.getId(), from, to, actorId, note);
        // CHECKED_IN đã có thông báo riêng từ clinical-service (visit.checked_in); hành động của chính chủ nuôi không cần báo lại.
        // NO_SHOW có sự kiện riêng (appointment.no_show) nên KHÔNG phát thêm status_changed (nếu không chủ nuôi nhận hai thông báo).
        boolean ownerNotifiable = to == AppointmentStatus.CONFIRMED || to == AppointmentStatus.CANCELLED;
        if (ownerNotifiable && !appointment.getOwnerId().equals(actorId)) {
            notifyOwner(appointment, actorId, false, reason);
        }
        if (to == AppointmentStatus.NO_SHOW) {
            publishNoShow(appointment, actorId);
        }
    }

    private void publishNoShow(Appointment appointment, UUID actorId) {
        outboxWriter.write(AGGREGATE, appointment.getId(), new AppointmentNoShowEvent(UuidV7.generate(clock), clock.instant(), actorId,
                appointment.getId(), appointment.getAppointmentCode(), appointment.getOwnerId(), appointment.getPetId(),
                appointment.getAppointmentDate(), appointment.getStartTime()));
    }

    /** Ghi sự kiện báo chủ nuôi vào outbox (cùng giao dịch); OutboxRelay gửi sang RabbitMQ sau khi commit. */
    private void notifyOwner(Appointment appointment, UUID actorId, boolean rescheduled, String reason) {
        outboxWriter.write(AGGREGATE, appointment.getId(), new AppointmentStatusChangedEvent(UuidV7.generate(clock), clock.instant(), actorId,
                appointment.getId(), appointment.getAppointmentCode(), appointment.getOwnerId(), appointment.getPetId(),
                appointment.getAppointmentDate(), appointment.getStartTime(), appointment.getStatus().name(), rescheduled, reason));
    }

    private void recordHistory(UUID appointmentId, AppointmentStatus from, AppointmentStatus to,
                               UUID actorId, String note) {
        historyRepository.save(AppointmentStatusHistory.builder()
                .appointmentId(appointmentId)
                .fromStatus(from)
                .toStatus(to)
                .changedByUserId(actorId)
                .notes(note)
                .build());
    }

    private Appointment findById(UUID id) {
        return appointmentRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.APPOINTMENT_NOT_FOUND));
    }

    private Appointment lockForUpdate(UUID id) {
        return appointmentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(ErrorCode.APPOINTMENT_NOT_FOUND));
    }

    private void requireRole(Actor actor, RoleCode required) {
        if (actor.role() != required) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
    }

    private Actor currentActor() {
        return new Actor(SecurityUtils.getCurrentUserId(),
                SecurityUtils.getCurrentRole().orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED)));
    }

    /** Lễ tân đặt hộ phải chọn đúng một tài khoản chủ thú cưng còn hoạt động. */
    private void assertActivePetOwner(UUID ownerId) {
        ApiResponse<UserSummaryResponse> response = authClient.getUser(ownerId);
        UserSummaryResponse owner = response == null ? null : response.getData();
        if (owner == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        if (!owner.roles().contains(RoleCode.PET_OWNER)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Người dùng được chọn không phải chủ thú cưng");
        }
        if (!"ACTIVE".equals(owner.status())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tài khoản chủ thú cưng không còn hoạt động");
        }
    }

    /**
     * Thú cưng phải tồn tại (chưa xóa) và thuộc đúng chủ nuôi; lấy tên + tên / SĐT chủ để chụp snapshot. Luôn gọi clinical-service (không còn
     * công tắc verify-pet vì snapshot cần dữ liệu này); clinical sập thì đặt lịch trả 503 (đóng cửa).
     */
    private PetSummaryResponse loadPet(UUID petId, UUID ownerId) {
        ApiResponse<PetSummaryResponse> response;
        try {
            response = petClient.getPet(petId);
        } catch (AppException e) {
            throw e;   // PET_NOT_FOUND do clinical-service trả
        } catch (RuntimeException e) {
            log.warn("Không kiểm tra được thú cưng {} ở clinical-service: {}", petId, e.getMessage());
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        PetSummaryResponse pet = response == null ? null : response.getData();
        // Thú cưng của người khác cũng trả PET_NOT_FOUND để không lộ sự tồn tại của hồ sơ
        if (pet == null || !ownerId.equals(pet.ownerId())) {
            throw new AppException(ErrorCode.PET_NOT_FOUND);
        }
        return pet;
    }

    /**
     * APT-yyyyMMdd-XXXXXX: khóa chính là UUID nên mã hiển thị dùng đuôi ngẫu nhiên; kiểm tra trùng rồi thử lại (UNIQUE là chốt chặn cuối).
     * Chạy trong transaction đặt lịch.
     */
    private String newCode() {
        String prefix = "APT-" + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        for (int i = 0; i < CODE_ATTEMPTS; i++) {
            String code = prefix + Codes.randomSuffix(6);
            if (!appointmentRepository.existsByAppointmentCode(code)) {
                return code;
            }
        }
        throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION, "Không sinh được mã lịch hẹn duy nhất, vui lòng thử lại");
    }
}
