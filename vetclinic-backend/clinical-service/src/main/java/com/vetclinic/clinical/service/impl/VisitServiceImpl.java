package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AppointmentClient;
import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailAction;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.ServiceKind;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.request.AssignDoctorRequest;
import com.vetclinic.clinical.dto.request.CheckInRequest;
import com.vetclinic.clinical.dto.request.ParaclinicalOrderRequest;
import com.vetclinic.clinical.dto.request.CloseVisitRequest;
import com.vetclinic.clinical.dto.request.OrderServicesRequest;
import com.vetclinic.clinical.dto.request.ReasonRequest;
import com.vetclinic.clinical.dto.request.ServiceOrderItem;
import com.vetclinic.clinical.dto.request.StartExamRequest;
import com.vetclinic.clinical.dto.request.UpdateMedicalRecordRequest;
import com.vetclinic.clinical.dto.request.VitalSignsRequest;
import com.vetclinic.clinical.dto.response.AppointmentSummaryResponse;
import com.vetclinic.clinical.dto.response.MedicalRecordResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitHistoryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.dto.response.VisitSummaryResponse;
import com.vetclinic.clinical.dto.response.VitalSignResponse;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.MedicalRecord;
import com.vetclinic.clinical.entity.Pet;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.entity.VitalSign;
import com.vetclinic.clinical.repository.ClinicServiceRepository;
import com.vetclinic.clinical.repository.MedicalRecordRepository;
import com.vetclinic.clinical.repository.PetRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import com.vetclinic.clinical.repository.PrescriptionRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.repository.VitalSignRepository;
import com.vetclinic.clinical.queue.QueueCoordinator;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.common.event.VisitCancelledEvent;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.VisitService;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.util.Codes;
import com.vetclinic.common.util.UuidV7;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.dto.PageResponse;
import com.vetclinic.common.event.PrescriptionCreatedEvent;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.lock.NamedLockService;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityUtils;
import jakarta.persistence.criteria.Predicate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VisitServiceImpl implements VisitService {

    /** Phí khám lâm sàng tổng quát: tạo cùng hóa đơn lúc tiếp đón (T01), thu xong mới phát phiếu vào hàng đợi Bác sĩ (BR-35). */
    static final String EXAM_SERVICE_CODE = "EXAM_GEN";

    private final PetVisitRepository visitRepository;
    private final PetRepository petRepository;
    private final PetVisitStatusHistoryRepository historyRepository;
    private final VisitServiceDetailRepository detailRepository;
    private final VitalSignRepository vitalSignRepository;
    private final MedicalRecordRepository medicalRecordRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final ClinicServiceRepository serviceRepository;
    private final AuthClient authClient;
    private final AppointmentClient appointmentClient;
    private final VisitSupport support;
    private final QueueCoordinator queue;
    private final VisitAssembler assembler;
    private final NamedLockService lockService;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public VisitServiceImpl(PetVisitRepository visitRepository, PetRepository petRepository,
                            PetVisitStatusHistoryRepository historyRepository,
                            VisitServiceDetailRepository detailRepository, VitalSignRepository vitalSignRepository,
                            MedicalRecordRepository medicalRecordRepository, PrescriptionRepository prescriptionRepository,
                            ClinicServiceRepository serviceRepository,
                            AuthClient authClient, AppointmentClient appointmentClient,
                            VisitSupport support, QueueCoordinator queue, VisitAssembler assembler,
                            NamedLockService lockService, OutboxWriter outboxWriter, Clock clock,
                            PlatformTransactionManager transactionManager) {
        this.visitRepository = visitRepository;
        this.petRepository = petRepository;
        this.historyRepository = historyRepository;
        this.detailRepository = detailRepository;
        this.vitalSignRepository = vitalSignRepository;
        this.medicalRecordRepository = medicalRecordRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.serviceRepository = serviceRepository;
        this.authClient = authClient;
        this.appointmentClient = appointmentClient;
        this.support = support;
        this.queue = queue;
        this.assembler = assembler;
        this.lockService = lockService;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private record Actor(UUID id, RoleCode role) {
    }

    // ================================================================== tiếp nhận (UC-19) và hàng đợi (UC-20)

    /**
     * KHÔNG @Transactional: lời gọi OpenFeign phải nằm ngoài transaction, và khóa tên phải còn giữ SAU KHI transaction
     * commit. Thứ tự: kiểm tra lịch hẹn qua Feign -> lấy khóa theo thú cưng -> [transaction: kiểm tra + tạo] -> nhả khóa.
     * Khóa theo thú cưng chặn hai lễ tân (hoặc bấm đúp) cùng tạo hai đợt khám đang diễn ra cho một thú cưng.
     */
    @Override
    public VisitResponse checkIn(CheckInRequest request) {
        Actor actor = currentActor();
        UUID petId = request.petId();
        UUID appointmentId = request.appointmentId();
        String reason = blankToNull(request.reasonForVisit());
        // Bác sĩ chọn ngay khi tiếp nhận (tùy chọn): kiểm tra qua Feign NGOÀI transaction
        UserSummaryResponse chosenDoctor = request.doctorId() == null ? null : fetchActiveDoctor(request.doctorId());
        UUID doctorId = chosenDoctor == null ? null : chosenDoctor.id();
        String doctorName = chosenDoctor == null ? null : chosenDoctor.fullName();

        if (appointmentId != null) {
            if (visitRepository.existsByAppointmentId(appointmentId)) {
                throw new AppException(ErrorCode.PET_HAS_OPEN_VISIT);
            }
            ApiResponse<AppointmentSummaryResponse> response = appointmentClient.getAppointment(appointmentId);
            AppointmentSummaryResponse appointment = response == null ? null : response.getData();
            if (appointment == null) {
                throw new AppException(ErrorCode.APPOINTMENT_NOT_FOUND);
            }
            if (!"CONFIRMED".equals(appointment.status()) || !LocalDate.now(clock).equals(appointment.appointmentDate())) {
                throw new AppException(ErrorCode.APPOINTMENT_NOT_CHECKABLE);
            }
            if (petId != null && !petId.equals(appointment.petId())) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Thú cưng không khớp với lịch hẹn");
            }
            petId = appointment.petId();
            if (reason == null) {
                reason = blankToNull(appointment.reason());
            }
        }
        if (petId == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Phải chọn thú cưng hoặc lịch hẹn để tiếp nhận");
        }

        UUID finalPetId = petId;
        String finalReason = reason;
        boolean emergency = Boolean.TRUE.equals(request.emergency());
        return lockService.runLocked(List.of("vetclinic:visit-pet:" + petId), () -> transactionTemplate.execute(
                tx -> createVisit(actor.id(), finalPetId, appointmentId, finalReason, emergency, doctorId, doctorName)));
    }

    private VisitResponse createVisit(UUID receptionistId, UUID petId, UUID appointmentId, String reasonForVisit,
                                      boolean emergency, UUID doctorId, String doctorName) {
        Pet pet = petRepository.findWithSpeciesByIdAndDeletedAtIsNull(petId)
                .orElseThrow(() -> new AppException(ErrorCode.PET_NOT_FOUND));
        if (visitRepository.existsByPetIdAndStatusIn(petId, VisitStatus.ACTIVE)) {
            throw new AppException(ErrorCode.PET_HAS_OPEN_VISIT);
        }
        if (appointmentId != null && visitRepository.existsByAppointmentId(appointmentId)) {
            throw new AppException(ErrorCode.PET_HAS_OPEN_VISIT, "Lịch hẹn này đã được tiếp nhận trước đó");
        }

        Instant now = clock.instant();
        PetVisit visit = PetVisit.builder()
                // Khóa là UUID nên mã hiển thị không dựa vào id: VIS-YYYY-XXXXXX (đuôi ngẫu nhiên), tính duy nhất do UNIQUE(visit_code) bảo đảm
                .visitCode(String.format("VIS-%d-%s", LocalDate.now(clock).getYear(), Codes.randomSuffix(6)))
                .pet(pet)
                .appointmentId(appointmentId)
                .doctorId(doctorId)
                .doctorNameSnapshot(doctorName)
                .receptionistId(receptionistId)
                .emergency(emergency)
                .reasonForVisit(reasonForVisit)
                .status(VisitStatus.WAITING)
                .checkedInAt(now)
                .build();
        visit = visitRepository.saveAndFlush(visit);

        support.record(visit.getId(), null, VisitStatus.WAITING, receptionistId,
                (appointmentId == null ? "Tiếp nhận khách vãng lai" : "Tiếp nhận khách có lịch hẹn #" + appointmentId)
                        + (emergency ? " (CẤP CỨU)" : "")
                        + (doctorId == null ? "" : ", chọn Bác sĩ #" + doctorId));
        // T01: lập dịch vụ khám + hóa đơn phí khám PENDING. Thu phí xong mới phát phiếu vào hàng đợi Bác sĩ (BR-35);
        // ca cấp cứu: phát phiếu ngay (priority 2), thu sau.
        ClinicService exam = serviceRepository.findByCode(EXAM_SERVICE_CODE).filter(ClinicService::isActive)
                .orElseThrow(() -> new AppException(ErrorCode.SERVICE_NOT_FOUND, "Chưa cấu hình dịch vụ khám " + EXAM_SERVICE_CODE));
        createDetail(visit.getId(), exam, 1, "Phí khám lâm sàng (lập lúc tiếp đón)", receptionistId, ServiceDetailStatus.PENDING);
        // Phiếu không phát trong giao dịch tiếp đón (v4.0): ca cấp cứu (hoặc phí khám không thu trước theo danh mục) nhận phiếu từ QueueIssuerListener
        // khi xử lý VisitCheckedInEvent bên dưới; ca thường chờ thu phí khám -> ServiceInvoicePaidEvent
        // Ghi vào outbox cùng giao dịch; OutboxRelay gửi sang RabbitMQ sau khi commit: appointment-service sẽ chuyển lịch hẹn sang CHECKED_IN
        outboxWriter.write(VisitSupport.OUTBOX_AGGREGATE, visit.getId(), new VisitCheckedInEvent(UuidV7.generate(clock), now, receptionistId, visit.getId(),
                visit.getVisitCode(), pet.getId(), appointmentId, emergency, doctorId, pet.getName(), pet.getSpecies().getCode(), pet.getOwnerId(),
                pet.getOwnerNameSnapshot(), doctorName));
        return assembler.detail(visit);   // kèm phí khám vừa lập và phiếu (ca cấp cứu) để màn hình tiếp đón hiển thị
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<VisitResponse> search(VisitStatus status, LocalDate date,
            UUID doctorId, UUID petId, String keyword, int page, int size) {
        Specification<PetVisit> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (doctorId != null) predicates.add(cb.equal(root.get("doctorId"), doctorId));
            if (petId != null) predicates.add(cb.equal(root.get("pet").get("id"), petId));
            if (date != null) {
                ZoneId zone = clock.getZone();
                predicates.add(cb.greaterThanOrEqualTo(root.get("checkedInAt"), date.atStartOfDay(zone).toInstant()));
                predicates.add(cb.lessThan(root.get("checkedInAt"), date.plusDays(1).atStartOfDay(zone).toInstant()));
            }
            if (keyword != null && !keyword.isBlank()) {
                String escaped = keyword.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                predicates.add(cb.like(cb.lower(root.get("visitCode")), "%" + escaped + "%", '\\'));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<PetVisit> result = visitRepository.findAll(spec, pageable);
        // Một truy vấn lịch sử cho cả trang (VisitTimeline), không N+1
        Map<UUID, VisitResponse> byId = assembler.summaries(result.getContent()).stream()
                .collect(Collectors.toMap(VisitResponse::id, Function.identity()));
        return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.isLast(), v -> byId.get(v.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public VisitResponse getById(UUID id) {
        Actor actor = currentActor();
        PetVisit visit = visitRepository.findWithPetById(id)
                .orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
        // Chủ nuôi chỉ xem được bệnh án của thú cưng mình; của người khác trả "không tìm thấy"
        if (actor.role() == RoleCode.PET_OWNER && !visit.getPet().getOwnerId().equals(actor.id())) {
            throw new AppException(ErrorCode.VISIT_NOT_FOUND);
        }
        return assembler.detail(visit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitHistoryResponse> getHistory(UUID id) {
        if (!visitRepository.existsById(id)) {
            throw new AppException(ErrorCode.VISIT_NOT_FOUND);
        }
        return historyRepository.findByVisitIdOrderByIdAsc(id).stream()
                .map(h -> new VisitHistoryResponse(h.getId(), h.getFromStatus(), h.getToStatus(),
                        h.getChangedByUserId(), h.getNotes(), h.getCreatedAt())).toList();
    }

    // ================================================================== phân công Bác sĩ (UC-21)

    /** KHÔNG @Transactional: kiểm tra Bác sĩ qua Feign NGOÀI transaction; việc ghi chạy trong transactionTemplate. */
    @Override
    public VisitResponse assignDoctor(UUID id, AssignDoctorRequest request) {
        Actor actor = currentActor();
        UserSummaryResponse doctor = fetchActiveDoctor(request.doctorId());
        return transactionTemplate.execute(tx -> applyAssignment(id, doctor));
    }

    private VisitResponse applyAssignment(UUID visitId, UserSummaryResponse doctor) {
        PetVisit visit = support.lock(visitId);   // khóa dòng + @Version: người đến sau thấy trạng thái mới nhất (FR-25)
        support.assertStatus(visit, VisitStatus.WAITING, "Chỉ xếp / chuyển được ca đang chờ khám (WAITING)");
        if (doctor.id().equals(visit.getDoctorId())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Ca này đã được xếp vào hàng đợi của bác sĩ này");
        }
        // KHÔNG kiểm tra Bác sĩ rảnh/bận (v3.0). Phiếu DOCTOR đang chờ (nếu đã nộp phí) đi theo, GIỮ số phiếu và giờ phát (Q3).
        // Trạng thái không đổi nên không ghi pet_visit_status_histories.
        visit.setDoctorId(doctor.id());
        visit.setDoctorNameSnapshot(doctor.fullName());   // chụp tên Bác sĩ lúc phân công (tránh N+1 khi liệt kê)
        visitRepository.save(visit);
        queue.reassignDoctorTicket(visit.getId(), doctor.id());
        return assembler.summary(visit).withDoctorInfo(doctor.roomNumber(), true);
    }

    @Override
    @Transactional
    public VisitResponse unassignDoctor(UUID id) {
        PetVisit visit = support.lock(id);
        support.assertStatus(visit, VisitStatus.WAITING, "Chỉ gỡ phân công được ca đang chờ khám (WAITING)");
        if (visit.getDoctorId() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Ca này chưa được xếp vào hàng đợi của bác sĩ nào");
        }
        visit.setDoctorId(null);
        visit.setDoctorNameSnapshot(null);
        visitRepository.save(visit);
        queue.reassignDoctorTicket(visit.getId(), null);   // sau commit: phiếu về danh sách chờ phân công (Lua), giữ số + score
        return assembler.summary(visit);
    }

    // ================================================================== khám giai đoạn 1 (UC-22, UC-23)

    @Override
    @Transactional
    public VisitResponse startExam(UUID id, StartExamRequest request) {
        UUID doctorId = SecurityUtils.getCurrentUserId();
        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.WAITING,
                "Chỉ có thể bắt đầu khám đối với ca đang ở trạng thái Chờ khám (WAITING)");
        // BR-12: một Bác sĩ chỉ khám một ca tại một thời điểm
        if (visitRepository.existsByDoctorIdAndStatus(doctorId, VisitStatus.IN_PROGRESS)) {
            throw new AppException(ErrorCode.DOCTOR_NOT_AVAILABLE);
        }

        // BR-35: ca phải có phiếu DOCTOR đang chờ (đã nộp phí khám, hoặc ca cấp cứu) -> phiếu IN_SERVICE (CLIN_1314 nếu chưa có)
        queue.startDoctorTicket(visit.getId(), doctorId);
        // T02: tạo hồ sơ khám (medical_records, 1-1 với đợt khám); lần đo sinh hiệu đầu tiên là tùy chọn
        medicalRecordRepository.save(MedicalRecord.builder().visitId(visit.getId())
                .anamnesis(request == null ? null : blankToNull(request.anamnesis())).build());
        if (request != null && request.vitalSigns() != null && !request.vitalSigns().isEmpty()) {
            saveVitalSigns(visit.getId(), request.vitalSigns(), doctorId);
        }
        // Thời điểm bắt đầu khám = created_at của dòng lịch sử này (không còn cột started_at)
        support.transition(visit, VisitStatus.IN_PROGRESS, doctorId, "Bác sĩ bắt đầu khám lâm sàng Giai đoạn 1");

        // Dịch vụ khám (lập lúc tiếp đón - T01) PENDING -> IN_PROGRESS. KHÔNG lập hóa đơn mới.
        for (VisitServiceDetail d : detailRepository.findByVisitIdOrderByIdAsc(visit.getId())) {
            if (d.getService().getCategory().is(ServiceCategory.EXAMINATION) && d.getStatus() == ServiceDetailStatus.PENDING) {
                d.setStatus(ServiceDetailStatus.IN_PROGRESS);
                detailRepository.save(d);
                support.recordDetail(d.getId(), ServiceDetailStatus.PENDING, ServiceDetailStatus.IN_PROGRESS, ServiceDetailAction.START_PROCESSING,
                        doctorId, null);
            }
        }
        return assembler.detail(visit);
    }

    @Override
    @Transactional
    public VitalSignResponse recordVitalSigns(UUID id, VitalSignsRequest request) {
        UUID doctorId = SecurityUtils.getCurrentUserId();
        if (request == null || request.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Phải nhập ít nhất một chỉ số sinh hiệu");
        }
        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.IN_PROGRESS, "Chỉ ghi sinh hiệu được khi ca đang khám (IN_PROGRESS)");
        return VitalSignResponse.of(saveVitalSigns(visit.getId(), request, doctorId));
    }

    /** Mỗi lần đo là MỘT dòng mới (không ghi đè lần đo trước); số liệu "hiện tại" là dòng ghi gần nhất. */
    private VitalSign saveVitalSigns(UUID visitId, VitalSignsRequest r, UUID recordedBy) {
        return vitalSignRepository.save(VitalSign.builder()
                .visitId(visitId)
                .weightKg(r.weightKg())
                .temperatureC(r.temperatureC())
                .heartRateBpm(r.heartRateBpm())
                .respiratoryRateBpm(r.respiratoryRateBpm())
                .bodyConditionScore(r.bodyConditionScore())
                .mucousMembraneColor(blankToNull(r.mucousMembraneColor()))
                .capillaryRefillSec(r.capillaryRefillSec())
                .notes(blankToNull(r.notes()))
                .recordedBy(recordedBy)
                .recordedAt(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public List<VitalSignResponse> vitalSigns(UUID id) {
        if (!visitRepository.existsById(id)) {
            throw new AppException(ErrorCode.VISIT_NOT_FOUND);
        }
        return vitalSignRepository.findByVisitIdOrderByRecordedAtAscIdAsc(id).stream().map(VitalSignResponse::of).toList();
    }

    @Override
    @Transactional
    public MedicalRecordResponse updateMedicalRecord(UUID id, UpdateMedicalRecordRequest request) {
        UUID doctorId = SecurityUtils.getCurrentUserId();
        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        // Khóa một chiều từ lúc chốt chuyên môn (BR-27 / NFR-06): CLIN_1317. "Mở lại hồ sơ" (T14) đưa ca về IN_PROGRESS thì sửa được lại.
        if (visit.getStatus() == VisitStatus.WAITING_PAYMENT || visit.getStatus() == VisitStatus.COMPLETED
                || visit.getStatus() == VisitStatus.CANCELLED) {
            throw new AppException(ErrorCode.MEDICAL_RECORD_LOCKED);
        }
        support.assertStatus(visit, VisitStatus.IN_PROGRESS, "Chỉ sửa được hồ sơ khám khi ca đang khám (IN_PROGRESS)");
        MedicalRecord record = medicalRecordRepository.findById(id)
                .orElseGet(() -> MedicalRecord.builder().visitId(id).build());
        if (request.version() != null && record.getVersion() != null && !request.version().equals(record.getVersion())) {
            throw new AppException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);   // đã có người / thiết bị khác sửa trước
        }
        if (request.anamnesis() != null) record.setAnamnesis(blankToNull(request.anamnesis()));
        if (request.clinicalFindings() != null) record.setClinicalFindings(blankToNull(request.clinicalFindings()));
        if (request.preliminaryDiagnosis() != null) record.setPreliminaryDiagnosis(blankToNull(request.preliminaryDiagnosis()));
        if (request.finalDiagnosis() != null) record.setFinalDiagnosis(blankToNull(request.finalDiagnosis()));
        if (request.treatmentPlan() != null) record.setTreatmentPlan(blankToNull(request.treatmentPlan()));
        if (request.doctorAdvice() != null) record.setDoctorAdvice(blankToNull(request.doctorAdvice()));
        if (request.followUpDate() != null) record.setFollowUpDate(request.followUpDate());
        return MedicalRecordResponse.of(medicalRecordRepository.saveAndFlush(record), false);
    }

    @Override
    @Transactional
    public VisitResponse orderParaclinical(UUID id, ParaclinicalOrderRequest request) {
        UUID doctorId = SecurityUtils.getCurrentUserId();
        List<UUID> serviceIds = request.serviceIds().stream().distinct().toList();
        List<ClinicService> services = loadActiveServices(serviceIds);
        if (services.stream().anyMatch(s -> !s.getCategory().isParaclinical())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Chỉ định cận lâm sàng chỉ nhận dịch vụ xét nghiệm (LABORATORY) hoặc chẩn đoán hình ảnh (IMAGING)");
        }

        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.IN_PROGRESS, "Chỉ chỉ định cận lâm sàng khi ca khám đang diễn ra (IN_PROGRESS)");
        if (support.isPhaseTwo(visit.getId())) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION, "Ca đã ở Giai đoạn 2: không chỉ định thêm cận lâm sàng");
        }
        // T04: phải có cân nặng và thân nhiệt (> 0) trước khi đẩy sang cận lâm sàng
        if (!vitalSignRepository.existsCompleteMeasurement(visit.getId())) {
            throw new AppException(ErrorCode.VITAL_SIGNS_REQUIRED);
        }
        // T04: các dịch vụ làm tại phòng đã chỉ định ở Giai đoạn 1 phải xong (COMPLETED) hoặc hủy (CANCELLED)
        if (detailRepository.countByVisitAndKindAndStatuses(visit.getId(), ServiceKind.IN_ROOM, ServiceDetailStatus.OPEN) > 0) {
            throw new AppException(ErrorCode.IN_ROOM_SERVICE_PENDING);
        }

        String notes = blankToNull(request.notes());
        List<VisitServiceDetail> ordered = new ArrayList<>();
        for (ClinicService service : services) {
            // Mỗi dịch vụ: dòng chỉ định PENDING + hóa đơn PENDING, cùng giao dịch. CHƯA vào hàng đợi phòng: khách nộp tiền xong mới có phiếu
            ordered.add(createDetail(visit.getId(), service, 1, notes, doctorId, ServiceDetailStatus.PENDING));
        }
        support.completeExamDetails(visit.getId(), doctorId);   // dịch vụ khám của GĐ 1 hoàn tất
        queue.finishDoctorTicket(visit.getId());         // phiếu DOCTOR -> DONE (lịch sử trong giao dịch, xóa khỏi Redis sau commit)
        // FR-33: ca rời IN_PROGRESS nên trạng thái Bác sĩ tính ra AVAILABLE: Bác sĩ gọi số tiếp theo mà không chờ kết quả
        support.transition(visit, VisitStatus.WAITING_RESULTS, doctorId,
                "Bác sĩ chỉ định cận lâm sàng, ca chuyển sang chờ kết quả và Bác sĩ được giải phóng");
        // Ca cấp cứu: phát phiếu phòng ngay (priority 2), thu sau. Dịch vụ CLS không cần thu trước (danh mục): phát phiếu ngay
        List<VisitServiceDetail> ticketNow = visit.isEmergency() ? ordered
                : ordered.stream().filter(d -> !d.getService().isRequiresPrepayment()).toList();
        Map<QueueType, List<UUID>> byRoom = new java.util.LinkedHashMap<>();
        for (VisitServiceDetail d : ticketNow) {
            byRoom.computeIfAbsent(d.getService().getCategory().queueTypeOrNull(), k -> new ArrayList<>()).add(d.getId());
        }
        byRoom.forEach((room, detailIds) -> queue.issueAfterCommit(visit, room, visit.isEmergency() ? 2 : 0, clock.instant(), detailIds,
                QueueCoordinator.roomKey(visit.getId(), room, detailIds)));   // phát sau commit; khóa dedupe chung với listener / dựng lại
        return assembler.detail(visit);
    }

    @Override
    @Transactional
    public VisitResponse orderInRoomServices(UUID id, OrderServicesRequest request) {
        UUID doctorId = SecurityUtils.getCurrentUserId();
        List<UUID> serviceIds = request.services().stream().map(ServiceOrderItem::serviceId).distinct().toList();
        Map<UUID, ClinicService> found = loadActiveServices(serviceIds).stream()
                .collect(Collectors.toMap(ClinicService::getId, Function.identity()));
        if (found.values().stream().anyMatch(s -> !s.getCategory().isInRoom())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Chỉ nhận dịch vụ thực hiện tại phòng (tiêm, tẩy giun, ngoại ký sinh, truyền dịch, thủ thuật, phẫu thuật)");
        }

        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.IN_PROGRESS, "Chỉ chỉ định dịch vụ khi ca khám đang diễn ra (IN_PROGRESS)");

        for (ServiceOrderItem item : request.services()) {
            // PENDING cho tới khi Bác sĩ bấm "Đã thực hiện"; hóa đơn PENDING lập ngay cùng giao dịch
            createDetail(visit.getId(), found.get(item.serviceId()), item.quantity() == null ? 1 : item.quantity(),
                    blankToNull(item.notes()), doctorId, ServiceDetailStatus.PENDING);
        }
        return assembler.detail(visit);
    }

    private List<ClinicService> loadActiveServices(List<UUID> serviceIds) {
        List<ClinicService> found = serviceRepository.findByIdInAndActiveTrue(serviceIds);
        if (found.size() != serviceIds.size()) {
            throw new AppException(ErrorCode.SERVICE_NOT_FOUND);
        }
        return found;
    }

    /**
     * Tạo dòng chỉ định + HÓA ĐƠN RIÊNG của nó trong cùng giao dịch (BR-19, FR-32). Đơn giá / VAT được snapshot vào hóa đơn
     * (BR-25), visit_service_details không chứa giá. Dịch vụ XN / CĐHA vào hàng đợi phòng khi thu tiền xong (queue_ticket_items).
     */
    private VisitServiceDetail createDetail(UUID visitId, ClinicService service, int quantity, String notes, UUID actorId,
                                            ServiceDetailStatus status) {
        VisitServiceDetail detail = detailRepository.saveAndFlush(VisitServiceDetail.builder()
                .visitId(visitId)
                .service(service)
                .quantity(quantity)
                .status(status)
                .notes(notes)
                .build());
        support.createInvoice(detail, service);
        support.recordDetail(detail.getId(), null, status, ServiceDetailAction.ORDERED, actorId, notes);
        return detail;
    }

    // ================================================================== khám giai đoạn 2 (UC-30, UC-33)

    @Override
    @Transactional
    public VisitResponse startPhase2(UUID id) {
        UUID doctorId = SecurityUtils.getCurrentUserId();
        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.RESULTS_READY,
                "Chỉ bắt đầu Giai đoạn 2 với ca đã có kết quả cận lâm sàng (RESULTS_READY)");
        // BR-12: một Bác sĩ chỉ khám một ca tại một thời điểm
        if (visitRepository.existsByDoctorIdAndStatus(doctorId, VisitStatus.IN_PROGRESS)) {
            throw new AppException(ErrorCode.DOCTOR_NOT_AVAILABLE);
        }
        queue.startDoctorTicket(visit.getId(), doctorId);   // phiếu ưu tiên (priority 1) -> IN_SERVICE (start_ticket.lua sau commit)
        // Hai giai đoạn của IN_PROGRESS phân biệt bằng lịch sử: đã qua WAITING_RESULTS thì là Giai đoạn 2
        support.transition(visit, VisitStatus.IN_PROGRESS, doctorId, "Bác sĩ bắt đầu khám Giai đoạn 2 (đọc kết quả cận lâm sàng)");
        return assembler.detail(visit);
    }

    @Override
    @Transactional
    public VisitResponse closeVisit(UUID id, CloseVisitRequest request) {
        UUID doctorId = SecurityUtils.getCurrentUserId();
        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.IN_PROGRESS, "Chỉ chốt được ca đang khám (IN_PROGRESS)");
        MedicalRecord record = medicalRecordRepository.findById(id)
                .orElseGet(() -> MedicalRecord.builder().visitId(id).build());
        // EX-12: chặn chốt ca khi chưa có chẩn đoán xác định. Ca không làm cận lâm sàng (chưa qua Giai đoạn 2) được dùng
        // chẩn đoán sơ bộ đã ghi trên hồ sơ khám (FR-54).
        String finalDiagnosis = blankToNull(request.finalDiagnosis());
        if (finalDiagnosis == null) {
            finalDiagnosis = blankToNull(record.getFinalDiagnosis());
        }
        if (finalDiagnosis == null && !support.isPhaseTwo(visit.getId())) {
            finalDiagnosis = blankToNull(record.getPreliminaryDiagnosis());
        }
        if (finalDiagnosis == null) {
            throw new AppException(ErrorCode.DIAGNOSIS_REQUIRED);
        }
        if (detailRepository.countByVisitAndKindAndStatuses(visit.getId(), ServiceKind.PARACLINICAL,
                ServiceDetailStatus.OPEN) > 0) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION, "Còn dịch vụ cận lâm sàng chưa hoàn tất");
        }
        if (!vitalSignRepository.existsCompleteMeasurement(visit.getId())) {
            throw new AppException(ErrorCode.VITAL_SIGNS_REQUIRED);
        }
        if (detailRepository.countByVisitAndKindAndStatuses(visit.getId(), ServiceKind.IN_ROOM, ServiceDetailStatus.OPEN) > 0) {
            throw new AppException(ErrorCode.IN_ROOM_SERVICE_PENDING);
        }

        record.setFinalDiagnosis(finalDiagnosis);
        if (request.treatmentPlan() != null) record.setTreatmentPlan(blankToNull(request.treatmentPlan()));
        if (request.doctorAdvice() != null) record.setDoctorAdvice(blankToNull(request.doctorAdvice()));
        if (request.followUpDate() != null) record.setFollowUpDate(request.followUpDate());
        medicalRecordRepository.save(record);
        support.completeExamDetails(visit.getId(), doctorId);   // dịch vụ khám hoàn tất khi Bác sĩ rời giai đoạn khám
        queue.finishDoctorTicket(visit.getId());         // phiếu DOCTOR -> DONE (mở lại hồ sơ thì không có phiếu)
        // Khóa hồ sơ chuyên môn một chiều (BR-27); thời điểm chốt = created_at của dòng lịch sử (không còn cột closed_at)
        support.transition(visit, VisitStatus.WAITING_PAYMENT, doctorId,
                "Bác sĩ chốt chuyên môn, khóa hồ sơ bệnh án và chuyển sang thanh toán");
        publishPrescriptionCreated(visit, doctorId);
        return assembler.detail(visit);
    }

    /**
     * Chốt ca có đơn thuốc: ghi outbox {@code PrescriptionCreatedEvent} CÙNG giao dịch chốt ca; billing tạo hóa đơn thuốc NHÁP (hoặc dựng lại nháp
     * nếu đây là lần chốt lại sau khi mở lại hồ sơ, T14). Không có đơn thì không phát gì. Khách / thú cưng / Bác sĩ chụp vào sự kiện để billing in
     * hóa đơn mà không gọi ngược clinical.
     */
    private void publishPrescriptionCreated(PetVisit visit, UUID doctorId) {
        prescriptionRepository.findWithItemsByVisitId(visit.getId()).ifPresent(prescription -> {
            Pet pet = visit.getPet();
            List<PrescriptionCreatedEvent.Item> items = prescription.getItems().stream()
                    .map(i -> new PrescriptionCreatedEvent.Item(i.getMedicineId(), i.getQuantity())).toList();
            String doctorName = visit.getDoctorNameSnapshot() == null ? "Bác sĩ" : visit.getDoctorNameSnapshot();
            outboxWriter.write(VisitSupport.OUTBOX_AGGREGATE, visit.getId(), new PrescriptionCreatedEvent(UuidV7.generate(clock), clock.instant(),
                    doctorId, prescription.getId(), visit.getId(), pet.getOwnerNameSnapshot(), pet.getOwnerPhoneSnapshot(), pet.getName(), doctorName, items));
        });
    }

    @Override
    @Transactional
    public VisitResponse cancelExam(UUID id, ReasonRequest request) {
        UUID doctorId = SecurityUtils.getCurrentUserId();
        PetVisit visit = support.lock(id);
        support.assertAssignedTo(visit, doctorId);
        support.assertStatus(visit, VisitStatus.IN_PROGRESS, "Chỉ hủy ca / chuyển viện được khi ca đang khám (IN_PROGRESS)");
        if (support.isPhaseTwo(visit.getId())) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Ca đã có kết quả cận lâm sàng: không hủy ca, hãy chốt ca để thu phí các dịch vụ đã làm");
        }
        if (detailRepository.countByVisitAndKindAndStatuses(visit.getId(), ServiceKind.IN_ROOM,
                List.of(ServiceDetailStatus.COMPLETED)) > 0) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Đã thực hiện dịch vụ tại phòng: không hủy ca, hãy chốt ca để thu phí dịch vụ đã làm");
        }
        String reason = request.reason().trim();
        cancelOpenDetails(visit.getId(), doctorId, "Hủy ca / chuyển viện: " + reason, true);
        queue.cancelActiveTickets(visit.getId(), reason);   // phiếu DOCTOR đang phục vụ -> CANCELLED
        // Lý do hủy ghi nguyên văn vào notes của dòng lịch sử (không còn cột cancel_reason)
        support.transition(visit, VisitStatus.CANCELLED, doctorId, reason);
        publishVisitCancelled(visit, doctorId, reason);
        return assembler.detail(visit);
    }

    /**
     * Hủy các dòng chỉ định còn dở (PENDING / IN_PROGRESS) kèm hóa đơn của chính chúng, cùng giao dịch (BR-33): hóa đơn chưa thu
     * -> CANCELLED, đã thu -> giữ PAID (khách tự bỏ, không hoàn). {@code includeExam = false} thì chỉ hủy dịch vụ cận lâm sàng
     * (khách bỏ về: dịch vụ khám và dịch vụ tại phòng đã làm giữ nguyên, hóa đơn chưa thu làm công nợ).
     */
    private void cancelOpenDetails(UUID visitId, UUID actorId, String reason, boolean includeExam) {
        for (VisitServiceDetail detail : detailRepository.findByVisitIdOrderByIdAsc(visitId)) {
            if (!ServiceDetailStatus.OPEN.contains(detail.getStatus())) {
                continue;
            }
            if (!includeExam && !detail.getService().getCategory().isParaclinical()) {
                continue;
            }
            ServiceDetailStatus from = detail.getStatus();
            detail.setStatus(ServiceDetailStatus.CANCELLED);
            detailRepository.save(detail);
            support.recordDetail(detail.getId(), from, ServiceDetailStatus.CANCELLED, ServiceDetailAction.CANCELLED, actorId, reason);
            support.settleInvoiceOfCancelled(detail, false);   // chưa thu -> CANCELLED; đã thu -> giữ PAID (không hoàn)
        }
    }

    @Override
    @Transactional
    public VisitResponse reopen(UUID id, ReasonRequest request) {
        Actor actor = currentActor();
        if (actor.role() != RoleCode.VETERINARIAN && actor.role() != RoleCode.ADMIN) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        String reason = request.reason().trim();
        PetVisit visit = support.lock(id);
        if (actor.role() == RoleCode.VETERINARIAN) {
            support.assertAssignedTo(visit, actor.id());
        }
        support.assertStatus(visit, VisitStatus.WAITING_PAYMENT, "Chỉ mở lại được ca đang chờ thanh toán (WAITING_PAYMENT)");
        UUID doctorId = visit.getDoctorId();
        // Bác sĩ phụ trách đang khám ca khác thì chưa mở lại được (BR-12)
        if (doctorId == null || visitRepository.existsByDoctorIdAndStatus(doctorId, VisitStatus.IN_PROGRESS)) {
            throw new AppException(ErrorCode.DOCTOR_NOT_AVAILABLE, "Bác sĩ phụ trách đang bận ca khác, hãy mở lại khi bác sĩ rảnh");
        }
        support.transition(visit, VisitStatus.IN_PROGRESS, actor.id(), "Mở lại hồ sơ để điều chỉnh chuyên môn: " + reason);
        return assembler.detail(visit);
    }

    @Override
    @Transactional
    public VisitResponse abandon(UUID id) {
        Actor actor = currentActor();
        PetVisit visit = support.lock(id);
        if (actor.role() == RoleCode.VETERINARIAN) {
            support.assertAssignedTo(visit, actor.id());
        } else if (actor.role() != RoleCode.RECEPTIONIST) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        if (visit.getStatus() != VisitStatus.RESULTS_READY && visit.getStatus() != VisitStatus.WAITING_RESULTS) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Chỉ đóng ca \"khách bỏ về\" được với ca đang chờ kết quả (WAITING_RESULTS) hoặc đã có kết quả (RESULTS_READY)");
        }
        // T12: không đóng ca khi kỹ thuật viên đang thực hiện dở một chỉ định (khách đang ở trong phòng)
        if (detailRepository.countByVisitAndKindAndStatuses(visit.getId(), ServiceKind.PARACLINICAL,
                List.of(ServiceDetailStatus.IN_PROGRESS)) > 0) {
            throw new AppException(ErrorCode.PARACLINICAL_SERVICE_IN_PROGRESS);
        }
        // Chỉ định cận lâm sàng chưa thực hiện bị hủy kèm hóa đơn; dịch vụ đã làm giữ hóa đơn PENDING làm công nợ
        cancelOpenDetails(visit.getId(), actor.id(), "Khách bỏ về: hủy chỉ định chưa thực hiện", false);
        queue.cancelActiveTickets(visit.getId(), "Khách bỏ về");   // phiếu phòng / phiếu ưu tiên đang chờ -> CANCELLED
        support.transition(visit, VisitStatus.COMPLETED, actor.id(),
                "Khách bỏ về: đóng ca, lưu kết quả vào bệnh án và ghi nợ viện phí (EX-11)");
        outboxWriter.write(VisitSupport.OUTBOX_AGGREGATE, visit.getId(), new VisitCompletedEvent(UuidV7.generate(clock), clock.instant(), actor.id(), visit.getId(),
                visit.getVisitCode(), visit.getPet().getId(), true, visit.getDoctorId(), visit.getDoctorNameSnapshot()));
        return assembler.detail(visit);
    }

    @Override
    @Transactional
    public VisitResponse cancel(UUID id, ReasonRequest request) {
        Actor actor = currentActor();
        if (actor.role() != RoleCode.RECEPTIONIST) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        PetVisit visit = support.lock(id);
        support.assertStatus(visit, VisitStatus.WAITING, "Chỉ hủy tiếp nhận được ca đang chờ khám (WAITING)");
        String reason = request.reason().trim();
        // Dịch vụ khám -> CANCELLED; phí khám chưa thu -> CANCELLED, đã thu -> giữ PAID (không hoàn); phiếu đang chờ -> CANCELLED
        cancelOpenDetails(visit.getId(), actor.id(), "Hủy tiếp nhận: " + reason, true);
        queue.cancelActiveTickets(visit.getId(), reason);
        // Lý do hủy ghi nguyên văn vào notes của dòng lịch sử (không còn cột cancel_reason)
        support.transition(visit, VisitStatus.CANCELLED, actor.id(), reason);
        publishVisitCancelled(visit, actor.id(), reason);
        return assembler.detail(visit);
    }

    /** reporting tăng {@code cancelled_visits}, notification báo màn hình: ghi outbox cùng giao dịch hủy. */
    private void publishVisitCancelled(PetVisit visit, UUID actorId, String reason) {
        outboxWriter.write(VisitSupport.OUTBOX_AGGREGATE, visit.getId(), new VisitCancelledEvent(UuidV7.generate(clock), clock.instant(), actorId,
                visit.getId(), visit.getVisitCode(), visit.getPet().getId(), visit.getDoctorId(), reason));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitResponse> myVisits() {
        return assembler.summaries(visitRepository.findDoctorVisits(
                SecurityUtils.getCurrentUserId(),
                List.of(VisitStatus.WAITING, VisitStatus.IN_PROGRESS, VisitStatus.RESULTS_READY)));
    }

    // ================================================================== không cần người dùng đăng nhập

    @Override
    @Transactional(readOnly = true)
    public VisitSummaryResponse summary(UUID visitId) {
        return visitRepository.findWithPetById(visitId).map(VisitServiceImpl::toSummary)
                .orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitSummaryResponse> summaries(Collection<UUID> visitIds) {
        if (visitIds == null || visitIds.isEmpty()) {
            return List.of();
        }
        return visitRepository.findWithPetByIdIn(visitIds).stream().map(VisitServiceImpl::toSummary).toList();
    }

    private static VisitSummaryResponse toSummary(PetVisit v) {
        return new VisitSummaryResponse(v.getId(), v.getVisitCode(), v.getStatus(), v.getPet().getId(),
                v.getPet().getName(), v.getPet().getOwnerId(), v.getDoctorId());
    }

    // ================================================================== helper

    private UserSummaryResponse fetchActiveDoctor(UUID doctorId) {
        ApiResponse<UserSummaryResponse> response = authClient.getUser(doctorId);
        UserSummaryResponse doctor = response == null ? null : response.getData();
        if (doctor == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        if (!doctor.roles().contains(RoleCode.VETERINARIAN) || !"ACTIVE".equals(doctor.status())) {
            throw new AppException(ErrorCode.DOCTOR_INACTIVE);
        }
        return doctor;
    }

    private Actor currentActor() {
        return new Actor(SecurityUtils.getCurrentUserId(),
                SecurityUtils.getCurrentRole().orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
