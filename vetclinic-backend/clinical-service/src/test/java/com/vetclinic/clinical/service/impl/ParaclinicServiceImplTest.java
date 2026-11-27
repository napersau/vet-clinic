package com.vetclinic.clinical.service.impl;

import com.vetclinic.common.event.DomainEvent;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.clinical.constant.LabEvaluation;
import com.vetclinic.clinical.constant.PaymentStatus;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.ServiceKind;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.request.LabResultItemRequest;
import com.vetclinic.clinical.dto.request.LabResultRequest;
import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.dto.response.QueueItemResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.entity.PetVisitStatusHistory;
import com.vetclinic.clinical.queue.QueueCoordinator;
import com.vetclinic.clinical.queue.QueueRedisRepository;
import com.vetclinic.clinical.queue.TicketView;
import com.vetclinic.clinical.repository.ServiceLabTestItemRepository;
import com.vetclinic.clinical.testsupport.TestData;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.PatientCalledEvent;
import com.vetclinic.clinical.dto.response.ServiceDetailResponse;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.ImagingAttachment;
import com.vetclinic.clinical.entity.ImagingResult;
import com.vetclinic.clinical.service.FileUrlSigner;
import com.vetclinic.clinical.entity.LabTestItem;
import com.vetclinic.clinical.entity.LabTestReferenceRange;
import com.vetclinic.clinical.entity.LabTestResult;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.ServiceInvoice;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.entity.VisitServiceDetailHistory;
import com.vetclinic.clinical.repository.ImagingAttachmentRepository;
import com.vetclinic.clinical.repository.ImagingResultRepository;
import com.vetclinic.clinical.repository.LabTestItemRepository;
import com.vetclinic.clinical.repository.LabTestReferenceRangeRepository;
import com.vetclinic.clinical.repository.LabTestResultRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import com.vetclinic.clinical.repository.ServiceInvoiceRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailHistoryRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.ImagingStorage;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.clinical.service.VisitTimeline;
import com.vetclinic.common.event.ParaclinicalResultsReadyEvent;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.Set;

import static com.vetclinic.clinical.testsupport.TestData.lid;
import static com.vetclinic.clinical.testsupport.TestData.CLOCK;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.IMAGING_TECH;
import static com.vetclinic.clinical.testsupport.TestData.LAB_TECH;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.cat;
import static com.vetclinic.clinical.testsupport.TestData.detail;
import static com.vetclinic.clinical.testsupport.TestData.dog;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.pet;
import static com.vetclinic.clinical.testsupport.TestData.service;
import static com.vetclinic.clinical.testsupport.TestData.OWNER;
import static com.vetclinic.clinical.testsupport.TestData.uid;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static com.vetclinic.clinical.testsupport.TestData.pid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParaclinicServiceImplTest {

    private static final ClinicService CBC = service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00");
    private static final ClinicService ULTRASOUND = service(6L, "IMG_US_AB", ServiceCategory.IMAGING, "200000.00");

    @Mock
    private VisitServiceDetailRepository detailRepository;
    @Mock
    private VisitServiceDetailHistoryRepository detailHistoryRepository;
    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private PetVisitStatusHistoryRepository historyRepository;
    @Mock
    private ServiceInvoiceRepository invoiceRepository;
    @Mock
    private LabTestItemRepository labTestItemRepository;
    @Mock
    private LabTestReferenceRangeRepository rangeRepository;
    @Mock
    private ServiceLabTestItemRepository serviceLabItemRepository;
    @Mock
    private LabTestResultRepository labResultRepository;
    @Mock
    private ImagingResultRepository imagingResultRepository;
    @Mock
    private ImagingAttachmentRepository attachmentRepository;
    @Mock
    private ImagingStorage storage;
    @Mock
    private OutboxWriter outboxWriter;
    @Mock
    private QueueRedisRepository redis;
    @Mock
    private QueueCoordinator ticketIssuer;   // phiếu xếp hàng (QueueCoordinator, bước 6.2)

    private ParaclinicServiceImpl service;
    private final FileUrlSigner urlSigner = new FileUrlSigner(new com.vetclinic.clinical.config.StorageProperties("./uploads",
            "test-secret-test-secret-0123456789", java.time.Duration.ofHours(1), List.of("image/jpeg")), CLOCK);

    @BeforeEach
    void setUp() {
        VisitSupport support = new VisitSupport(visitRepository, historyRepository, detailRepository,
                detailHistoryRepository, invoiceRepository, com.vetclinic.clinical.testsupport.TestData.priceBook(), outboxWriter, CLOCK);
        VisitAssembler assembler = new VisitAssembler(detailRepository, labResultRepository, imagingResultRepository,
                attachmentRepository, invoiceRepository, new VisitTimeline(historyRepository), ticketIssuer,
                mock(com.vetclinic.clinical.repository.VitalSignRepository.class), mock(com.vetclinic.clinical.repository.MedicalRecordRepository.class));
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ParaclinicServiceImpl(detailRepository, detailHistoryRepository, redis, ticketIssuer, CLOCK, visitRepository,
                labTestItemRepository, rangeRepository, serviceLabItemRepository, labResultRepository, imagingResultRepository,
                attachmentRepository, support, assembler, storage, urlSigner, tm);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ================================================================== hàng đợi (phiếu xếp hàng, 17/11/2026)

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);   // CLOCK cố định

    /** Phiếu phòng đọc từ Redis, chứa các dịch vụ {@code detailIds} (vsdIds trong Hash phiếu). */
    private static TicketView ticket(long id, QueueType type, long visitId, int no, int priority, TicketStatus status, long... detailIds) {
        return com.vetclinic.clinical.testsupport.TestData.ticket(id, type, visitId, null, no, priority, status,
                java.util.Arrays.stream(detailIds).mapToObj(com.vetclinic.clinical.testsupport.TestData::lid).toList());
    }

    @Test
    void queue_followsTheTicketOrder_expandsEachTicketIntoItsOpenServices_andExposesTheLabStage() {
        PetVisit normal = visit(10L, VisitStatus.WAITING_RESULTS, DOCTOR);
        PetVisit emergency = visit(11L, VisitStatus.WAITING_RESULTS, DOCTOR);
        emergency.setEmergency(true);
        PetVisit other = visit(12L, VisitStatus.WAITING_RESULTS, DOCTOR);

        VisitServiceDetail d1 = detail(1L, 10L, CBC, ServiceDetailStatus.IN_PROGRESS);
        VisitServiceDetail d2 = detail(2L, 11L, CBC, ServiceDetailStatus.PENDING);
        VisitServiceDetail d3 = detail(3L, 12L, CBC, ServiceDetailStatus.PENDING);
        VisitServiceDetail d4 = detail(4L, 12L, CBC, ServiceDetailStatus.CANCELLED);   // dịch vụ đã hủy của phiếu: không hiện

        var lab = QueueType.LABORATORY;
        var waiting = TicketStatus.WAITING;
        // Phiếu CHỜ từ Sorted Set của Redis; phiếu ĐANG PHỤC VỤ (đã rời Sorted Set) tìm qua dịch vụ đang thực hiện của phòng; gộp rồi sắp priority DESC, giờ phát
        when(redis.waitingViews(TODAY, lab, null)).thenReturn(List.of(
                ticket(71L, lab, 11L, 2, 2, waiting, 2L),
                ticket(72L, lab, 12L, 3, 0, waiting, 3L, 4L)));
        when(detailRepository.findInProgressByQueueType("LABORATORY")).thenReturn(List.of(d1));
        when(ticketIssuer.openTicketsOf(lid(10L))).thenReturn(List.of(ticket(70L, lab, 10L, 1, 0, TicketStatus.IN_SERVICE, 1L)));
        when(detailRepository.findAllById(anyCollection())).thenReturn(List.of(d1, d2, d3, d4));
        when(visitRepository.findWithPetByIdIn(anyCollection())).thenReturn(List.of(normal, emergency, other));
        when(detailRepository.findByVisitIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(d1, d2, d3, d4));
        // d1 đã lấy mẫu xong (đang phân tích); d2, d3 mới chỉ định
        when(detailHistoryRepository.findByVisitServiceDetailIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                history(1L, "ORDERED"), history(2L, "ORDERED"), history(3L, "ORDERED"), history(1L, "START_PROCESSING"),
                history(1L, "SAMPLE_COLLECTED")));

        List<QueueItemResponse> queue = service.queue(QueueType.LABORATORY);

        assertThat(queue).extracting(QueueItemResponse::serviceDetailId).containsExactly(lid(2L), lid(1L), lid(3L));
        assertThat(queue).extracting(QueueItemResponse::ticketNo).containsExactly(2, 1, 3);
        assertThat(queue).extracting(QueueItemResponse::ticketId).containsExactly(com.vetclinic.clinical.testsupport.TestData.tid(71L), com.vetclinic.clinical.testsupport.TestData.tid(70L), com.vetclinic.clinical.testsupport.TestData.tid(72L));
        assertThat(queue.get(0).emergency()).isTrue();
        assertThat(queue.get(0).ticketPriority()).isEqualTo(2);
        assertThat(queue).extracting(QueueItemResponse::stage).containsExactly("QUEUED", "ANALYZING", "QUEUED");
        assertThat(queue).extracting(QueueItemResponse::labStatus).containsOnlyNulls();   // huy hiệu liên phòng chỉ có ở hàng đợi CĐHA
    }

    @Test
    void queue_isEmptyWhenNoTicketHasBeenIssued_unpaidServicesNeverShowUp() {
        when(redis.waitingViews(TODAY, QueueType.IMAGING, null)).thenReturn(List.of());
        assertThat(service.queue(QueueType.IMAGING)).isEmpty();
        verify(detailRepository, never()).findAllById(anyCollection());
    }

    private static VisitServiceDetailHistory history(long detailId, String action) {
        return VisitServiceDetailHistory.builder().visitServiceDetailId(lid(detailId)).action(com.vetclinic.clinical.constant.ServiceDetailAction.valueOf(action))
                .toStatus(ServiceDetailStatus.PENDING).changedByUserId(LAB_TECH).build();
    }

    @Test
    void imagingQueue_showsTheCrossRoomLabBadge_samplingThenSampleCollected_EX09() {
        PetVisit sampling = visit(10L, VisitStatus.WAITING_RESULTS, DOCTOR);
        PetVisit collected = visit(11L, VisitStatus.WAITING_RESULTS, DOCTOR);
        PetVisit noLab = visit(12L, VisitStatus.WAITING_RESULTS, DOCTOR);
        VisitServiceDetail img1 = detail(1L, 10L, ULTRASOUND, ServiceDetailStatus.PENDING);
        VisitServiceDetail img2 = detail(2L, 11L, ULTRASOUND, ServiceDetailStatus.PENDING);
        VisitServiceDetail img3 = detail(3L, 12L, ULTRASOUND, ServiceDetailStatus.PENDING);
        VisitServiceDetail lab1 = detail(4L, 10L, CBC, ServiceDetailStatus.IN_PROGRESS);
        VisitServiceDetail lab2 = detail(5L, 11L, CBC, ServiceDetailStatus.IN_PROGRESS);
        var img = QueueType.IMAGING;
        var waiting = TicketStatus.WAITING;
        when(redis.waitingViews(TODAY, img, null)).thenReturn(List.of(
                ticket(80L, img, 10L, 1, 0, waiting, 1L), ticket(81L, img, 11L, 2, 0, waiting, 2L), ticket(82L, img, 12L, 3, 0, waiting, 3L)));
        when(detailRepository.findAllById(anyCollection())).thenReturn(List.of(img1, img2, img3));
        when(visitRepository.findWithPetByIdIn(anyCollection())).thenReturn(List.of(sampling, collected, noLab));
        when(detailRepository.findByVisitIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(img1, img2, img3, lab1, lab2));
        when(detailHistoryRepository.findByVisitServiceDetailIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                history(4L, "START_PROCESSING"), history(5L, "START_PROCESSING"), history(5L, "SAMPLE_COLLECTED")));

        List<QueueItemResponse> queue = service.queue(QueueType.IMAGING);

        assertThat(queue).extracting(QueueItemResponse::labStatus).containsExactly("SAMPLING", "SAMPLE_COLLECTED", null);
        assertThat(queue).extracting(QueueItemResponse::stage).containsOnlyNulls();   // stage chỉ dành cho phòng Lab
    }

    @Test
    void queue_ofANonParaclinicalCategory_isRejected() {
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.queue(QueueType.DOCTOR));
    }

    // ================================================================== lấy mẫu (phòng Lab)

    private static final ClinicService DEWORM_SVC = service(4L, "DEWORM", "DEWORMING", "50000.00");

    @Test
    void sampleCollected_logsTheActionWithoutChangingTheStatus() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        when(detailHistoryRepository.findByVisitServiceDetailIdInOrderByIdAsc(List.of(lid(1L))))
                .thenReturn(List.of(history(1L, "ORDERED"), history(1L, "START_PROCESSING")));

        service.sampleCollected(lid(1L));

        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);
        ArgumentCaptor<VisitServiceDetailHistory> audit = ArgumentCaptor.forClass(VisitServiceDetailHistory.class);
        verify(detailHistoryRepository).save(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo(com.vetclinic.clinical.constant.ServiceDetailAction.SAMPLE_COLLECTED);
        assertThat(audit.getValue().getFromStatus()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);
        assertThat(audit.getValue().getToStatus()).isEqualTo(ServiceDetailStatus.IN_PROGRESS);
    }

    @Test
    void sampleCollected_refusedForTheWrongRoleKindStateOrWhenAlreadyCollected() {
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.sampleCollected(lid(1L)));

        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        // Chưa vào phòng (PENDING)
        stubLocks(detail(2L, 101L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.sampleCollected(lid(2L)));
        // Ca không còn chờ kết quả
        stubLocks(detail(3L, 102L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_PAYMENT);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.sampleCollected(lid(3L)));
        // Đã lấy mẫu rồi
        stubLocks(detail(4L, 103L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        when(detailHistoryRepository.findByVisitServiceDetailIdInOrderByIdAsc(List.of(lid(4L))))
                .thenReturn(List.of(history(4L, "START_PROCESSING"), history(4L, "SAMPLE_COLLECTED")));
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.sampleCollected(lid(4L)));
        verify(detailHistoryRepository, never()).save(any());
    }

    // ================================================================== dịch vụ tại phòng (FR-30)

    private static final ClinicService DEWORM = service(4L, "DEWORM", "DEWORMING", "50000.00");
    private static final ClinicService EXAM = service(1L, "EXAM_GEN", ServiceCategory.EXAMINATION, "100000.00");

    @Test
    void perform_completesAnInRoomService_recordingStartThenCompleted() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, DEWORM, ServiceDetailStatus.PENDING), VisitStatus.IN_PROGRESS);

        ServiceDetailResponse response = service.perform(lid(1L));

        assertThat(response.status()).isEqualTo(ServiceDetailStatus.COMPLETED);
        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);
        ArgumentCaptor<VisitServiceDetailHistory> audit = ArgumentCaptor.forClass(VisitServiceDetailHistory.class);
        verify(detailHistoryRepository, Mockito.times(2)).save(audit.capture());
        assertThat(audit.getAllValues()).extracting(VisitServiceDetailHistory::getAction).containsExactly(com.vetclinic.clinical.constant.ServiceDetailAction.START_PROCESSING, com.vetclinic.clinical.constant.ServiceDetailAction.COMPLETED);
    }

    @Test
    void perform_refusedForTheWrongRoleDoctorServiceOrState() {
        // Kỹ thuật viên không ghi nhận dịch vụ tại phòng
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, DEWORM, ServiceDetailStatus.PENDING), VisitStatus.IN_PROGRESS);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.perform(lid(1L)));

        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        // Chỉ định cận lâm sàng không "Đã thực hiện" kiểu này
        stubLocks(detail(2L, 101L, CBC, ServiceDetailStatus.PENDING), VisitStatus.IN_PROGRESS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.perform(lid(2L)));
        // Ca đã khóa
        stubLocks(detail(3L, 102L, DEWORM, ServiceDetailStatus.PENDING), VisitStatus.WAITING_PAYMENT);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.perform(lid(3L)));
        // Đã hoàn tất rồi
        stubLocks(detail(4L, 103L, DEWORM, ServiceDetailStatus.COMPLETED), VisitStatus.IN_PROGRESS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.perform(lid(4L)));
        // Bác sĩ khác không phải Bác sĩ được gán
        loginAs(TestData.OTHER_DOCTOR, RoleCode.VETERINARIAN);
        stubLocks(detail(5L, 104L, DEWORM, ServiceDetailStatus.PENDING), VisitStatus.IN_PROGRESS);
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.perform(lid(5L)));
    }

    @Test
    void cancel_ofAnInRoomService_byTheAssignedDoctor_cancelsItsInvoiceToo() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, DEWORM, ServiceDetailStatus.PENDING), VisitStatus.IN_PROGRESS);
        ServiceInvoice invoice = ServiceInvoice.builder().id(lid(50L)).visitServiceDetailId(lid(1L)).unitPrice(new BigDecimal("50000.00"))
                .vatRate(BigDecimal.ZERO).build();
        when(invoiceRepository.findByVisitServiceDetailId(lid(1L))).thenReturn(Optional.of(invoice));

        service.cancel(lid(1L), "Khách từ chối tẩy giun", null);

        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(invoice.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(outboxWriter, never()).write(any(), any(), any(DomainEvent.class));   // dịch vụ tại phòng không ảnh hưởng RESULTS_READY
    }

    @Test
    void cancel_ofAnInRoomService_refusedWhenTheVisitIsLocked_andTheExamFeeCannotBeCancelledAlone() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubLocks(detail(1L, 100L, DEWORM, ServiceDetailStatus.PENDING), VisitStatus.WAITING_PAYMENT);
        assertError(ErrorCode.INVALID_STATUS_TRANSITION, () -> service.cancel(lid(1L), "x", null));

        stubLocks(detail(2L, 101L, EXAM, ServiceDetailStatus.IN_PROGRESS), VisitStatus.IN_PROGRESS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.cancel(lid(2L), "x", null));   // dùng "Hủy ca / Chuyển viện"
    }

    // ================================================================== kết quả xét nghiệm (UC-25)

    @Test
    void submitLab_evaluatesAgainstTheSpeciesRangeAndSnapshotsIt() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        LabTestItem wbc = labItem(1L, "WBC");
        LabTestItem plt = labItem(4L, "PLT");
        when(labTestItemRepository.findAllById(List.of((short) 1, (short) 4))).thenReturn(List.of(wbc, plt));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId((short) 1, (short) 1)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId((short) 4, (short) 1)).thenReturn(Optional.of(range(plt, "200.00", "500.00")));
        when(detailRepository.countByVisitAndKindAndStatuses(lid(100L), ServiceKind.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.submitLabResults(lid(1L), new LabResultRequest(List.of(
                new LabResultItemRequest((short) 1, new BigDecimal("21.00"), null),     // Chó: 21 > 17 -> HIGH
                new LabResultItemRequest((short) 4, new BigDecimal("150.00"), "Tiểu cầu thấp"))));

        ArgumentCaptor<List<LabTestResult>> saved = ArgumentCaptor.forClass(List.class);
        verify(labResultRepository).saveAll(saved.capture());
        LabTestResult highWbc = saved.getValue().get(0);
        // Không còn cột evaluation: nhãn tính từ giá trị so với khoảng tham chiếu đã snapshot (DANH_SACH_KIEU_ENUM mục 11)
        assertThat(LabEvaluation.of(highWbc.getResultValue(), highWbc.getMinValueSnapshot(), highWbc.getMaxValueSnapshot()))
                .isEqualTo(LabEvaluation.HIGH);
        assertThat(highWbc.getMinValueSnapshot()).isEqualByComparingTo("6.00");   // snapshot (BR-24)
        assertThat(highWbc.getMaxValueSnapshot()).isEqualByComparingTo("17.00");
        assertThat(highWbc.getPerformedBy()).isEqualTo(LAB_TECH);
        LabTestResult lowPlt = saved.getValue().get(1);
        assertThat(LabEvaluation.of(lowPlt.getResultValue(), lowPlt.getMinValueSnapshot(), lowPlt.getMaxValueSnapshot()))
                .isEqualTo(LabEvaluation.LOW);
        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);
    }

    @Test
    void submitLab_aConfiguredPackageRequiresEveryItem_andListsTheMissingOnes() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        LabTestItem wbc = labItem(1L, "WBC");
        LabTestItem plt = labItem(4L, "PLT");
        when(serviceLabItemRepository.findItemIdsByServiceId(com.vetclinic.clinical.testsupport.TestData.sid(5))).thenReturn(List.of((short) 1, (short) 4));   // gói CBC gồm WBC + PLT
        when(labTestItemRepository.findAllById(List.of((short) 1))).thenReturn(List.of(wbc));
        when(labTestItemRepository.findAllById(Set.of((short) 4))).thenReturn(List.of(plt));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId((short) 1, (short) 1)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));

        var ex = Assertions.assertThrows(AppException.class,
                () -> service.submitLabResults(lid(1L), new LabResultRequest(List.of(new LabResultItemRequest((short) 1, new BigDecimal("10.00"), null)))));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_DATA);   // SYS_1001
        assertThat(ex.getMessage()).contains("PLT");
        verify(labResultRepository, never()).saveAll(anyList());
    }

    @Test
    void submitLab_aCompletePackageIsAccepted() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        LabTestItem wbc = labItem(1L, "WBC");
        LabTestItem plt = labItem(4L, "PLT");
        when(serviceLabItemRepository.findItemIdsByServiceId(com.vetclinic.clinical.testsupport.TestData.sid(5))).thenReturn(List.of((short) 1, (short) 4));
        when(labTestItemRepository.findAllById(List.of((short) 4, (short) 1))).thenReturn(List.of(wbc, plt));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId((short) 1, (short) 1)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId((short) 4, (short) 1)).thenReturn(Optional.of(range(plt, "200.00", "500.00")));
        when(detailRepository.countByVisitAndKindAndStatuses(lid(100L), ServiceKind.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.submitLabResults(lid(1L), new LabResultRequest(List.of(
                new LabResultItemRequest((short) 4, new BigDecimal("300.00"), null), new LabResultItemRequest((short) 1, new BigDecimal("9.00"), null))));

        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.COMPLETED);
        verify(labResultRepository).saveAll(anyList());
    }

    @Test
    void labResults_returnsTheSavedValuesOfALabDetail_andRejectsOtherKinds() {
        VisitServiceDetail lab = detail(1L, 100L, CBC, ServiceDetailStatus.COMPLETED);
        when(detailRepository.findById(lid(1L))).thenReturn(Optional.of(lab));
        LabTestItem wbc = labItem(1L, "WBC");
        LabTestResult saved = LabTestResult.builder().id(9L).visitServiceDetailId(lid(1L)).labTestItem(wbc)
                .resultValue(new BigDecimal("21.00")).minValueSnapshot(new BigDecimal("6.00")).maxValueSnapshot(new BigDecimal("17.00"))
                .performedBy(LAB_TECH).build();
        when(labResultRepository.findByVisitServiceDetailIdInOrderByIdAsc(List.of(lid(1L)))).thenReturn(List.of(saved));

        var results = service.labResults(lid(1L));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).evaluation()).isEqualTo(LabEvaluation.HIGH);
        when(detailRepository.findById(lid(2L))).thenReturn(Optional.of(detail(2L, 100L, ULTRASOUND, ServiceDetailStatus.COMPLETED)));
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.labResults(lid(2L)));
        assertError(ErrorCode.SERVICE_DETAIL_NOT_FOUND, () -> service.labResults(lid(99L)));
    }

    @Test
    void submitLab_usesTheRangeOfThePetsOwnSpecies() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        PetVisit catVisit = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        catVisit.setPet(pet(pid(12), cat()));
        VisitServiceDetail d = detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS);
        when(detailRepository.findByIdForUpdate(lid(1L))).thenReturn(Optional.of(d));
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(catVisit));
        LabTestItem wbc = labItem(1L, "WBC");
        when(labTestItemRepository.findAllById(List.of((short) 1))).thenReturn(List.of(wbc));
        // Mèo (species 2): WBC 5.5 - 19.5, nên 18.0 là bình thường (với Chó 6 - 17 thì là cao)
        when(rangeRepository.findByLabTestItemIdAndSpeciesId((short) 1, (short) 2)).thenReturn(Optional.of(range(wbc, "5.50", "19.50")));
        when(detailRepository.countByVisitAndKindAndStatuses(lid(100L), ServiceKind.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.submitLabResults(lid(1L), new LabResultRequest(List.of(new LabResultItemRequest((short) 1, new BigDecimal("18.00"), null))));

        ArgumentCaptor<List<LabTestResult>> saved = ArgumentCaptor.forClass(List.class);
        verify(labResultRepository).saveAll(saved.capture());
        LabTestResult catResult = saved.getValue().get(0);
        assertThat(LabEvaluation.of(catResult.getResultValue(), catResult.getMinValueSnapshot(), catResult.getMaxValueSnapshot()))
                .isEqualTo(LabEvaluation.NORMAL);
        verify(rangeRepository, never()).findByLabTestItemIdAndSpeciesId((short) 1, (short) 1);
    }

    @Test
    void submitLab_lastOpenServiceMovesTheVisitToResultsReadyAndNotifiesTheDoctor() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        LabTestItem wbc = labItem(1L, "WBC");
        when(labTestItemRepository.findAllById(List.of((short) 1))).thenReturn(List.of(wbc));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId((short) 1, (short) 1)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));
        // Không còn chỉ định Lab / CĐHA nào dở dang
        when(detailRepository.countByVisitAndKindAndStatuses(lid(100L), ServiceKind.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(0L);

        service.submitLabResults(lid(1L), new LabResultRequest(List.of(new LabResultItemRequest((short) 1, new BigDecimal("10.00"), null))));

        ArgumentCaptor<PetVisitStatusHistory> history =
                ArgumentCaptor.forClass(PetVisitStatusHistory.class);
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getFromStatus()).isEqualTo(VisitStatus.WAITING_RESULTS);
        assertThat(history.getValue().getToStatus()).isEqualTo(VisitStatus.RESULTS_READY);

        assertThat(history.getValue().getChangedByUserId()).isNull();   // T06: hệ thống tự chuyển

        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter, Mockito.times(1)).write(eq("VISIT"), any(), event.capture());   // chỉ visit.results_ready (v3.0)
        ParaclinicalResultsReadyEvent ready = event.getAllValues().stream().filter(ParaclinicalResultsReadyEvent.class::isInstance)
                .map(ParaclinicalResultsReadyEvent.class::cast).findFirst().orElseThrow();
        assertThat(ready.visitId()).isEqualTo(lid(100L));
        assertThat(ready.doctorId()).isEqualTo(DOCTOR);
        assertThat(ready.actorUserId()).isNull();
    }

    @Test
    void resultsReady_Q6_issuesAPriorityDoctorTicket_andClosesTheRoomTicket() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS);
        PetVisit visit = visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR);
        when(detailRepository.findByIdForUpdate(lid(1L))).thenReturn(Optional.of(d));
        when(visitRepository.findByIdForUpdate(lid(100L))).thenReturn(Optional.of(visit));
        LabTestItem wbc = labItem(1L, "WBC");
        when(labTestItemRepository.findAllById(List.of((short) 1))).thenReturn(List.of(wbc));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId((short) 1, (short) 1)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));
        when(detailRepository.countByVisitAndKindAndStatuses(lid(100L), ServiceKind.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(0L);

        service.submitLabResults(lid(1L), new LabResultRequest(List.of(new LabResultItemRequest((short) 1, new BigDecimal("10.00"), null))));

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.RESULTS_READY);
        verify(ticketIssuer).refreshRoomTicketOf(lid(100L), lid(1L));       // phiếu phòng Lab -> DONE
        // phiếu DOCTOR priority 1 do QueueIssuerListener phát khi nhận ParaclinicalResultsReadyEvent (đã ghi vào outbox ở dòng trên)
    }

    @Test
    void submitLab_whileAnotherServiceIsStillOpen_keepsTheVisitWaiting() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        LabTestItem wbc = labItem(1L, "WBC");
        when(labTestItemRepository.findAllById(List.of((short) 1))).thenReturn(List.of(wbc));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId((short) 1, (short) 1)).thenReturn(Optional.of(range(wbc, "6.00", "17.00")));
        when(detailRepository.countByVisitAndKindAndStatuses(lid(100L), ServiceKind.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.submitLabResults(lid(1L), new LabResultRequest(List.of(new LabResultItemRequest((short) 1, new BigDecimal("10.00"), null))));

        verify(historyRepository, never()).save(any());
        verify(outboxWriter, never()).write(any(), any(), any(DomainEvent.class));
    }

    @Test
    void submitLab_rejectsInvalidSubmissions() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        LabResultRequest request = new LabResultRequest(List.of(new LabResultItemRequest((short) 1, new BigDecimal("10.00"), null)));

        // Chưa bấm "Bắt đầu thực hiện"
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.submitLabResults(lid(1L), request));

        // Nhập hai lần cùng một chỉ số
        stubLocks(detail(2L, 101L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.submitLabResults(lid(2L), new LabResultRequest(List.of(
                new LabResultItemRequest((short) 1, new BigDecimal("1.00"), null), new LabResultItemRequest((short) 1, new BigDecimal("2.00"), null)))));

        // Chỉ số không tồn tại
        stubLocks(detail(3L, 102L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        when(labTestItemRepository.findAllById(List.of((short) 1))).thenReturn(List.of());
        assertError(ErrorCode.LAB_ITEM_NOT_FOUND, () -> service.submitLabResults(lid(3L), request));

        // Chưa cấu hình khoảng tham chiếu cho loài này
        stubLocks(detail(4L, 103L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        when(labTestItemRepository.findAllById(List.of((short) 1))).thenReturn(List.of(labItem(1L, "WBC")));
        when(rangeRepository.findByLabTestItemIdAndSpeciesId((short) 1, (short) 1)).thenReturn(Optional.empty());
        assertError(ErrorCode.LAB_REFERENCE_NOT_FOUND, () -> service.submitLabResults(lid(4L), request));

        // Chỉ định siêu âm không nhập được như xét nghiệm; và KTV CĐHA không được nhập kết quả Lab
        stubLocks(detail(5L, 104L, ULTRASOUND, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.submitLabResults(lid(5L), request));
        verify(labResultRepository, never()).saveAll(anyList());
    }

    @Test
    void labEvaluation_boundariesAreInclusive() {
        BigDecimal min = new BigDecimal("6.00");
        BigDecimal max = new BigDecimal("17.00");
        assertThat(LabEvaluation.of(new BigDecimal("6.00"), min, max)).isEqualTo(LabEvaluation.NORMAL);
        assertThat(LabEvaluation.of(new BigDecimal("17.00"), min, max)).isEqualTo(LabEvaluation.NORMAL);
        assertThat(LabEvaluation.of(new BigDecimal("5.99"), min, max)).isEqualTo(LabEvaluation.LOW);
        assertThat(LabEvaluation.of(new BigDecimal("17.01"), min, max)).isEqualTo(LabEvaluation.HIGH);
    }

    // ================================================================== hủy chỉ định (EX-08)

    @Test
    void cancel_theLastOpenService_movesTheVisitToResultsReady() {
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, ULTRASOUND, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        when(detailRepository.countByVisitAndKindAndStatuses(lid(100L), ServiceKind.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(0L);

        ServiceDetailResponse response = service.cancel(lid(1L), "  Thú cưng quá hung dữ  ", null);

        assertThat(response.status()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(lastDetailHistory().getReason()).isEqualTo("Thú cưng quá hung dữ");
        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        verify(outboxWriter).write(eq("VISIT"), any(), any(ParaclinicalResultsReadyEvent.class));
    }

    @Test
    void cancel_whileOtherServicesAreOpen_keepsTheVisitWaiting() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);   // Bác sĩ đổi hướng xử lý
        stubLocks(detail(1L, 100L, ULTRASOUND, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        when(detailRepository.countByVisitAndKindAndStatuses(lid(100L), ServiceKind.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.cancel(lid(1L), "Đổi phác đồ", null);

        verify(outboxWriter, never()).write(any(), any(), any(DomainEvent.class));
    }

    @Test
    void cancel_refusedForFinishedServicesAndForTheWrongRoom() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.COMPLETED), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.cancel(lid(1L), "x", null));

        stubLocks(detail(2L, 101L, ULTRASOUND, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.cancel(lid(2L), "x", null));
    }

    @Test
    void cancel_alsoCancelsTheServiceInvoiceInTheSameTransaction_BR33() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        ServiceInvoice invoice = ServiceInvoice.builder().id(lid(50L)).visitServiceDetailId(lid(1L))
                .unitPrice(new BigDecimal("250000.00")).vatRate(BigDecimal.ZERO).build();
        when(invoiceRepository.findByVisitServiceDetailId(lid(1L))).thenReturn(Optional.of(invoice));
        when(detailRepository.countByVisitAndKindAndStatuses(lid(100L), ServiceKind.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(1L);

        service.cancel(lid(1L), "Khách xin hủy", null);

        // Bất biến v3.0: dịch vụ CANCELLED <=> hóa đơn của chính dịch vụ đó CANCELLED
        assertThat(invoice.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(invoiceRepository).save(invoice);
    }

    private ServiceInvoice paidInvoice(long detailId, String price) {
        ServiceInvoice paid = ServiceInvoice.builder().id(lid(50L)).visitServiceDetailId(lid(detailId)).unitPrice(new BigDecimal(price))
                .vatRate(BigDecimal.ZERO).paymentStatus(PaymentStatus.PAID).build();
        when(invoiceRepository.findByVisitServiceDetailId(lid(detailId))).thenReturn(Optional.of(paid));
        return paid;
    }

    @Test
    void cancel_ofAPaidParaclinicalService_byTheCustomer_keepsTheInvoicePaid_noRefund() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        ServiceInvoice paid = paidInvoice(1L, "250000.00");

        service.cancel(lid(1L), "Khách xin về, không làm nữa", "CUSTOMER");

        assertThat(d.getStatus()).isEqualTo(ServiceDetailStatus.CANCELLED);
        assertThat(paid.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);   // khách tự hủy: không hoàn tiền
        verify(invoiceRepository, never()).save(paid);
        verify(ticketIssuer).refreshRoomTicketOf(lid(100L), lid(1L));
    }

    @Test
    void cancel_ofAPaidParaclinicalService_byTheClinic_refundsTheInvoice() {
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        stubLocks(detail(1L, 100L, ULTRASOUND, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        ServiceInvoice paid = paidInvoice(1L, "200000.00");

        service.cancel(lid(1L), "Máy siêu âm hỏng", "CLINIC");

        assertThat(paid.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(invoiceRepository).save(paid);
    }

    @Test
    void cancel_withoutAReasonType_defaultsToClinicForTheDoctor_andCustomerForTheTechnician() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        ServiceInvoice byDoctor = paidInvoice(1L, "250000.00");
        service.cancel(lid(1L), "Đổi hướng điều trị", null);
        assertThat(byDoctor.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);

        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(2L, 101L, CBC, ServiceDetailStatus.PENDING), VisitStatus.WAITING_RESULTS);
        ServiceInvoice byTech = paidInvoice(2L, "250000.00");
        service.cancel(lid(2L), "Khách bỏ về", null);
        assertThat(byTech.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void cancel_ofACompletedInRoomService_isRefusedAsWrongState() {
        // Sau "Mở lại hồ sơ" (T14) dịch vụ tại phòng đã làm xong không hủy được nữa
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubLocks(detail(1L, 100L, DEWORM, ServiceDetailStatus.COMPLETED), VisitStatus.IN_PROGRESS);

        assertError(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, () -> service.cancel(lid(1L), "Muốn hủy", null));
    }

    // ================================================================== kết quả CĐHA (UC-26)

    @Test
    void submitImaging_savesResultAndAttachments_thenMovesVisitToResultsReady() {
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        VisitServiceDetail d = detail(1L, 100L, ULTRASOUND, ServiceDetailStatus.IN_PROGRESS);
        when(detailRepository.findById(lid(1L))).thenReturn(Optional.of(d));
        stubLocks(d, VisitStatus.WAITING_RESULTS);
        when(storage.store(any())).thenReturn(new ImagingStorage.StoredFile("imaging/2026/10/a.jpg", "sieu-am.jpg", 1234L, "image/jpeg"));
        when(imagingResultRepository.saveAndFlush(any(ImagingResult.class))).thenAnswer(inv -> {
            ImagingResult r = inv.getArgument(0);
            r.setId(lid(700L));
            return r;
        });
        when(detailRepository.countByVisitAndKindAndStatuses(lid(100L), ServiceKind.PARACLINICAL, ServiceDetailStatus.OPEN)).thenReturn(0L);

        ServiceDetailResponse response = service.submitImagingResult(lid(1L), "Gan to nhẹ", "Nghi viêm gan",
                List.of(new MockMultipartFile("files", "sieu-am.jpg", "image/jpeg", new byte[]{1})));

        assertThat(response.status()).isEqualTo(ServiceDetailStatus.COMPLETED);
        // Chỉ lưu file_key (không lưu URL), kèm người tải lên
        ArgumentCaptor<List<ImagingAttachment>> saved = ArgumentCaptor.forClass(List.class);
        verify(attachmentRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(a -> {
            assertThat(a.getFileKey()).isEqualTo("imaging/2026/10/a.jpg");
            assertThat(a.getOriginalFileName()).isEqualTo("sieu-am.jpg");
            assertThat(a.getFileSize()).isEqualTo(1234L);
            assertThat(a.getUploadedBy()).isEqualTo(IMAGING_TECH);
            assertThat(a.getImagingResultId()).isEqualTo(lid(700L));
        });
        verify(outboxWriter).write(eq("VISIT"), any(), any(ParaclinicalResultsReadyEvent.class));
        verify(storage, never()).deleteQuietly(any());
    }

    // ================================================================== URL ký tạm của ảnh (Quy chuẩn 7.5)

    private void stubAttachmentOfPetOwnedBy(UUID owner) {
        ImagingAttachment a = ImagingAttachment.builder().id(lid(800L)).imagingResultId(lid(700L)).fileKey("imaging/2026/10/a.jpg").build();
        when(attachmentRepository.findById(lid(800L))).thenReturn(Optional.of(a));
        when(imagingResultRepository.findById(lid(700L))).thenReturn(Optional.of(ImagingResult.builder().id(lid(700L)).visitServiceDetailId(lid(1L)).build()));
        when(detailRepository.findById(lid(1L))).thenReturn(Optional.of(detail(1L, 100L, ULTRASOUND, ServiceDetailStatus.COMPLETED)));
        PetVisit visit = visit(100L, VisitStatus.COMPLETED, DOCTOR);
        visit.getPet().setOwnerId(owner);
        when(visitRepository.findWithPetById(lid(100L))).thenReturn(Optional.of(visit));
    }

    @Test
    void attachmentUrl_staffGetsASignedUrlWithExpiry_ownerOnlyForTheirOwnPet() {
        stubAttachmentOfPetOwnedBy(OWNER);

        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        var signed = service.attachmentUrl(lid(800L));
        assertThat(signed.url()).startsWith("/files/imaging/2026/10/a.jpg?exp=").contains("&sig=");
        assertThat(signed.expiresAt()).isEqualTo(CLOCK.instant().plusSeconds(3600));   // hạn 1 giờ

        loginAs(OWNER, RoleCode.PET_OWNER);
        assertThat(service.attachmentUrl(lid(800L)).url()).startsWith("/files/imaging/2026/10/a.jpg?exp=");

        loginAs(uid(77), RoleCode.PET_OWNER);   // chủ nuôi khác: không lộ sự tồn tại của ảnh
        assertError(ErrorCode.RESOURCE_NOT_FOUND, () -> service.attachmentUrl(lid(800L)));
    }

    @Test
    void attachmentUrl_unknownAttachment_isNotFound() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        assertError(ErrorCode.RESOURCE_NOT_FOUND, () -> service.attachmentUrl(lid(999L)));
    }

    @Test
    void submitImaging_whenDatabaseWriteFails_deletesTheFilesAlreadyStored() {
        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        when(detailRepository.findById(lid(1L))).thenReturn(Optional.of(detail(1L, 100L, ULTRASOUND, ServiceDetailStatus.IN_PROGRESS)));
        when(storage.store(any())).thenReturn(new ImagingStorage.StoredFile("imaging/2026/10/a.jpg", "a.jpg", 10L, "image/jpeg"));
        // Chỉ định bị xóa / không khóa được trong lúc xử lý -> transaction thất bại sau khi tệp đã lưu
        when(detailRepository.findByIdForUpdate(lid(1L))).thenReturn(Optional.empty());

        assertError(ErrorCode.SERVICE_DETAIL_NOT_FOUND, () -> service.submitImagingResult(lid(1L), "Mô tả", "Kết luận",
                List.of(new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1}))));

        verify(storage).deleteQuietly("imaging/2026/10/a.jpg");   // không để lại tệp mồ côi
    }

    @Test
    void submitImaging_validatesInputBeforeTouchingTheDiskOrDatabase() {
        MockMultipartFile image = new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1});

        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.submitImagingResult(lid(1L), "Mô tả", "Kết luận", List.of(image)));

        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.submitImagingResult(lid(1L), "  ", "Kết luận", List.of(image)));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.submitImagingResult(lid(1L), "Mô tả", null, List.of(image)));
        assertError(ErrorCode.INVALID_IMAGING_FILE, () -> service.submitImagingResult(lid(1L), "Mô tả", "Kết luận", List.of()));
        assertError(ErrorCode.INVALID_IMAGING_FILE, () -> service.submitImagingResult(lid(1L), "Mô tả", "Kết luận", null));

        verify(storage, never()).store(any());
        verify(detailRepository, never()).findByIdForUpdate(any());
    }

    // ================================================================== fixtures

    @Test
    void submitLab_rejectsAnItemThatDoesNotBelongToTheOrderedService() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        stubLocks(detail(1L, 100L, CBC, ServiceDetailStatus.IN_PROGRESS), VisitStatus.WAITING_RESULTS);
        when(serviceLabItemRepository.findItemIdsByServiceId(com.vetclinic.clinical.testsupport.TestData.sid(5))).thenReturn(List.of((short) 1, (short) 4));   // CBC chỉ gồm WBC, PLT
        LabTestItem glucose = labItem(9L, "GLU");
        when(labTestItemRepository.findAllById(List.of((short) 9))).thenReturn(List.of(glucose));

        assertError(ErrorCode.LAB_ITEM_NOT_IN_SERVICE, () -> service.submitLabResults(lid(1L),
                new LabResultRequest(List.of(new LabResultItemRequest((short) 9, new BigDecimal("5.00"), null)))));
        verify(labResultRepository, never()).saveAll(anyList());
    }

    @Test
    void labForm_listsOnlyTheServicesItemsWithTheRangeOfThePetsSpecies() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        VisitServiceDetail d = detail(1L, 100L, CBC, ServiceDetailStatus.PENDING);
        when(detailRepository.findById(lid(1L))).thenReturn(Optional.of(d));
        when(visitRepository.findWithPetById(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR)));
        LabTestItem wbc = labItem(1L, "WBC");
        LabTestItem glucose = labItem(9L, "GLU");
        when(serviceLabItemRepository.findItemIdsByServiceId(com.vetclinic.clinical.testsupport.TestData.sid(5))).thenReturn(List.of((short) 1));
        when(labTestItemRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(glucose, wbc));
        LabTestReferenceRange dogRange = range(wbc, "6.00", "17.00");
        LabTestReferenceRange catRange = LabTestReferenceRange.builder().labTestItem(wbc).species(cat())
                .minValue(new BigDecimal("5.50")).maxValue(new BigDecimal("19.50")).build();
        when(rangeRepository.findByLabTestItemIdIn(List.of((short) 1))).thenReturn(List.of(dogRange, catRange));

        var form = service.labForm(lid(1L));

        assertThat(form).extracting(i -> i.code()).containsExactly("WBC");
        assertThat(form.get(0).ranges()).hasSize(1);
        assertThat(form.get(0).ranges().get(0).minValue()).isEqualByComparingTo("6.00");   // visit() là thú cưng loài Chó
    }

    @Test
    void labForm_withoutConfigurationShowsEveryItem_andIsForTheLabOnly() {
        loginAs(LAB_TECH, RoleCode.LAB_TECHNICIAN);
        when(detailRepository.findById(lid(1L))).thenReturn(Optional.of(detail(1L, 100L, CBC, ServiceDetailStatus.PENDING)));
        when(visitRepository.findWithPetById(lid(100L))).thenReturn(Optional.of(visit(100L, VisitStatus.WAITING_RESULTS, DOCTOR)));
        when(labTestItemRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(labItem(1L, "WBC"), labItem(4L, "PLT")));

        assertThat(service.labForm(lid(1L))).hasSize(2);

        loginAs(IMAGING_TECH, RoleCode.IMAGING_TECHNICIAN);
        assertError(ErrorCode.ACCESS_DENIED, () -> service.labForm(lid(1L)));
    }

    /** Khóa chỉ định rồi khóa đợt khám, đúng thứ tự mà dịch vụ thật dùng. */
    private VisitServiceDetail stubLocks(VisitServiceDetail detail, VisitStatus visitStatus) {
        PetVisit visit = visit(1L, visitStatus, DOCTOR);
        visit.setId(detail.getVisitId());
        when(detailRepository.findByIdForUpdate(detail.getId())).thenReturn(Optional.of(detail));
        when(visitRepository.findByIdForUpdate(detail.getVisitId())).thenReturn(Optional.of(visit));
        return detail;
    }

    private static LabTestItem labItem(long id, String code) {
        return LabTestItem.builder().id((short) id).code(code).name(code).unit("10^9/L").build();
    }

    private static LabTestReferenceRange range(LabTestItem item, String min, String max) {
        return LabTestReferenceRange.builder().labTestItem(item).species(dog())
                .minValue(new BigDecimal(min)).maxValue(new BigDecimal(max)).build();
    }

    private VisitServiceDetailHistory lastDetailHistory() {
        ArgumentCaptor<VisitServiceDetailHistory> captor = ArgumentCaptor.forClass(VisitServiceDetailHistory.class);
        verify(detailHistoryRepository, Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
