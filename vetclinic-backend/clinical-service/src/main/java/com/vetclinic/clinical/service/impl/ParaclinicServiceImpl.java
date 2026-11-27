package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.constant.ServiceDetailAction;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.constant.TicketStatus;
import com.vetclinic.clinical.dto.response.LabResultResponse;
import com.vetclinic.clinical.repository.ServiceLabTestItemRepository;
import com.vetclinic.clinical.queue.QueueCoordinator;
import com.vetclinic.clinical.queue.QueueRedisRepository;
import com.vetclinic.clinical.queue.TicketView;
import com.vetclinic.clinical.dto.response.ReferenceRangeResponse;
import com.vetclinic.clinical.dto.response.LabTestItemResponse;
import com.vetclinic.clinical.constant.LabEvaluation;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.request.LabResultItemRequest;
import com.vetclinic.clinical.dto.request.LabResultRequest;
import com.vetclinic.clinical.dto.response.QueueItemResponse;
import com.vetclinic.clinical.dto.response.ServiceDetailResponse;
import com.vetclinic.clinical.entity.ImagingAttachment;
import com.vetclinic.clinical.entity.ImagingResult;
import com.vetclinic.clinical.entity.LabTestItem;
import com.vetclinic.clinical.entity.LabTestReferenceRange;
import com.vetclinic.clinical.entity.LabTestResult;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.entity.VisitServiceDetailHistory;
import com.vetclinic.clinical.repository.ImagingAttachmentRepository;
import com.vetclinic.clinical.repository.ImagingResultRepository;
import com.vetclinic.clinical.repository.LabTestItemRepository;
import com.vetclinic.clinical.repository.LabTestReferenceRangeRepository;
import com.vetclinic.clinical.repository.LabTestResultRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailHistoryRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.service.FileUrlSigner;
import com.vetclinic.clinical.service.ImagingStorage;
import com.vetclinic.clinical.service.ParaclinicService;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityUtils;
import java.util.UUID;
import java.util.HashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Các thao tác ghi dùng transaction READ_COMMITTED và khóa dòng theo thứ tự: chỉ định -> đợt khám.
 * Lý do: hai kỹ thuật viên (Lab và CĐHA) có thể cùng hoàn tất hai dịch vụ cuối của một ca. Với mức cô lập mặc định
 * REPEATABLE READ, người đến sau chờ khóa đợt khám nhưng vẫn đếm theo ảnh chụp cũ nên không thấy dịch vụ kia đã xong,
 * và ca kẹt mãi ở WAITING_RESULTS. READ_COMMITTED + khóa trước khi đếm khiến người đến sau luôn thấy kết quả mới nhất.
 */
@Slf4j
@Service
public class ParaclinicServiceImpl implements ParaclinicService {

    private static final int MAX_FILES = 10;

    private static final String REASON_CLINIC = "CLINIC";
    private static final String REASON_CUSTOMER = "CUSTOMER";

    private final VisitServiceDetailRepository detailRepository;
    private final VisitServiceDetailHistoryRepository detailHistoryRepository;
    private final QueueRedisRepository redis;
    private final QueueCoordinator queue;
    private final java.time.Clock clock;
    private final PetVisitRepository visitRepository;
    private final LabTestItemRepository labTestItemRepository;
    private final LabTestReferenceRangeRepository rangeRepository;
    private final ServiceLabTestItemRepository serviceLabItemRepository;
    private final LabTestResultRepository labResultRepository;
    private final ImagingResultRepository imagingResultRepository;
    private final ImagingAttachmentRepository attachmentRepository;
    private final VisitSupport support;
    private final VisitAssembler assembler;
    private final ImagingStorage storage;
    private final FileUrlSigner urlSigner;
    private final TransactionTemplate readCommittedTemplate;

    public ParaclinicServiceImpl(VisitServiceDetailRepository detailRepository,
                                 VisitServiceDetailHistoryRepository detailHistoryRepository,
                                 QueueRedisRepository redis, QueueCoordinator queue, java.time.Clock clock,
                                 PetVisitRepository visitRepository, LabTestItemRepository labTestItemRepository,
                                 LabTestReferenceRangeRepository rangeRepository,
                                 ServiceLabTestItemRepository serviceLabItemRepository,
                                 LabTestResultRepository labResultRepository,
                                 ImagingResultRepository imagingResultRepository,
                                 ImagingAttachmentRepository attachmentRepository, VisitSupport support,
                                 VisitAssembler assembler, ImagingStorage storage, FileUrlSigner urlSigner,
                                 PlatformTransactionManager transactionManager) {
        this.detailRepository = detailRepository;
        this.detailHistoryRepository = detailHistoryRepository;
        this.redis = redis;
        this.queue = queue;
        this.clock = clock;
        this.visitRepository = visitRepository;
        this.labTestItemRepository = labTestItemRepository;
        this.rangeRepository = rangeRepository;
        this.serviceLabItemRepository = serviceLabItemRepository;
        this.labResultRepository = labResultRepository;
        this.imagingResultRepository = imagingResultRepository;
        this.attachmentRepository = attachmentRepository;
        this.support = support;
        this.assembler = assembler;
        this.storage = storage;
        this.urlSigner = urlSigner;
        this.readCommittedTemplate = new TransactionTemplate(transactionManager);
        this.readCommittedTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private record Ctx(VisitServiceDetail detail, PetVisit visit) {
    }

    // ================================================================== hàng đợi

    /**
     * Hàng đợi phòng đọc từ PHIẾU XẾP HÀNG trên Redis: phiếu đang chờ lấy từ Sorted Set (thứ tự theo score: priority rồi giờ thu tiền), phiếu đang
     * phục vụ (đã rời Sorted Set) tìm qua các dịch vụ đang thực hiện của phòng; mỗi phiếu bung ra các dịch vụ còn mở của nó (đứng liền nhau). Dịch vụ chưa
     * nộp tiền không có phiếu nên không xuất hiện. Gọi số / bắt đầu phiếu ở QueueTicketService.
     */
    @Override
    @Transactional(readOnly = true)
    public List<QueueItemResponse> queue(QueueType queueType) {
        requireParaclinical(queueType);
        List<TicketView> tickets = new ArrayList<>(redis.waitingViews(java.time.LocalDate.now(clock), queueType, null));
        Set<UUID> seen = tickets.stream().map(TicketView::id).collect(Collectors.toCollection(HashSet::new));
        for (UUID visitId : detailRepository.findInProgressByQueueType(queueType.name()).stream().map(VisitServiceDetail::getVisitId).distinct().toList()) {
            for (TicketView t : queue.openTicketsOf(visitId)) {
                if (t.queueType() == queueType && seen.add(t.id())) {
                    tickets.add(t);   // phiếu IN_SERVICE của phòng
                }
            }
        }
        if (tickets.isEmpty()) {
            return List.of();
        }
        tickets.sort(java.util.Comparator.comparingInt(TicketView::priority).reversed()
                .thenComparing(TicketView::issuedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparingInt(TicketView::ticketNo));
        Map<UUID, VisitServiceDetail> details = detailRepository.findAllById(tickets.stream().flatMap(t -> t.vsdIds().stream()).toList()).stream()
                .collect(Collectors.toMap(VisitServiceDetail::getId, d -> d));
        List<Row> rows = new ArrayList<>();
        for (TicketView t : tickets) {
            t.vsdIds().stream().sorted()
                    .map(details::get)
                    .filter(d -> d != null && ServiceDetailStatus.OPEN.contains(d.getStatus()))
                    .forEach(d -> rows.add(new Row(d, t)));
        }
        return toItems(queueType, rows);
    }

    private record Row(VisitServiceDetail detail, TicketView ticket) {
    }

    private static void requireParaclinical(QueueType queueType) {
        if (queueType == QueueType.DOCTOR) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Chỉ có hàng đợi cho xét nghiệm và chẩn đoán hình ảnh");
        }
    }

    /** Giữ nguyên thứ tự của {@code rows} (đã xếp theo phiếu). */
    private List<QueueItemResponse> toItems(QueueType queueType, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> visitIds = rows.stream().map(r -> r.detail().getVisitId()).distinct().toList();
        Map<UUID, PetVisit> visits = visitRepository.findWithPetByIdIn(visitIds).stream()
                .collect(Collectors.toMap(PetVisit::getId, v -> v));
        Map<UUID, List<VisitServiceDetail>> siblings = detailRepository.findByVisitIdInOrderByIdAsc(visitIds).stream()
                .collect(Collectors.groupingBy(VisitServiceDetail::getVisitId));
        Map<UUID, ServiceDetailAction> latestAction = latestActions(siblings.values().stream().flatMap(List::stream)
                .map(VisitServiceDetail::getId).toList());

        return rows.stream()
                .filter(r -> visits.containsKey(r.detail().getVisitId()))
                .map(r -> {
                    VisitServiceDetail d = r.detail();
                    TicketView t = r.ticket();
                    PetVisit v = visits.get(d.getVisitId());
                    return new QueueItemResponse(d.getId(), v.getId(), v.getVisitCode(), v.getPet().getId(),
                            v.getPet().getName(), v.getPet().getSpecies().getName(), d.getService().getId(),
                            d.getService().getName(), d.getService().getCategory().getCode(), v.isEmergency(), d.getStatus(),
                            queueType == QueueType.LABORATORY ? labStage(d, latestAction.get(d.getId())) : null,
                            queueType == QueueType.IMAGING
                                    ? labBadge(siblings.getOrDefault(d.getVisitId(), List.of()), latestAction) : null,
                            d.getNotes(), d.getCreatedAt(), t.id(), t.ticketNo(), t.priority(), t.status(),
                            t.issuedAt(), t.lastCalledAt());
                })
                .toList();
    }

    /** action mới nhất của từng chỉ định (lịch sử luôn có dòng ORDERED đầu tiên). */
    private Map<UUID, ServiceDetailAction> latestActions(List<UUID> detailIds) {
        Map<UUID, ServiceDetailAction> latest = new HashMap<>();
        for (VisitServiceDetailHistory h : detailHistoryRepository.findByVisitServiceDetailIdInOrderByIdAsc(detailIds)) {
            latest.put(h.getVisitServiceDetailId(), h.getAction());   // đã sắp theo id tăng dần: dòng cuối thắng
        }
        return latest;
    }

    /** Trạng thái con của dịch vụ Lab: QUEUED (ORDERED) / SAMPLING (START_PROCESSING) / ANALYZING (SAMPLE_COLLECTED). */
    static String labStage(VisitServiceDetail d, ServiceDetailAction latestAction) {
        if (d.getStatus() == ServiceDetailStatus.PENDING) {
            return "QUEUED";
        }
        if (d.getStatus() != ServiceDetailStatus.IN_PROGRESS) {
            return null;
        }
        return latestAction == ServiceDetailAction.SAMPLE_COLLECTED ? "ANALYZING" : "SAMPLING";
    }

    /**
     * EX-09 / FR-36: huy hiệu liên phòng trên hàng đợi CĐHA. SAMPLING nếu có dịch vụ Lab của ca đang lấy mẫu; SAMPLE_COLLECTED nếu
     * các dịch vụ Lab đang làm đều đã lấy mẫu xong (sẵn sàng chụp); null nếu không có dịch vụ Lab IN_PROGRESS.
     */
    static String labBadge(List<VisitServiceDetail> siblings, Map<UUID, ServiceDetailAction> latestAction) {
        boolean anyLab = false;
        for (VisitServiceDetail s : siblings) {
            if (s.getService().getCategory().is(ServiceCategory.LABORATORY) && s.getStatus() == ServiceDetailStatus.IN_PROGRESS) {
                anyLab = true;
                if (latestAction.getOrDefault(s.getId(), ServiceDetailAction.START_PROCESSING) != ServiceDetailAction.SAMPLE_COLLECTED) {
                    return "SAMPLING";
                }
            }
        }
        return anyLab ? "SAMPLE_COLLECTED" : null;
    }

    // ================================================================== thao tác trên một chỉ định

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ServiceDetailResponse sampleCollected(UUID detailId) {
        UUID actor = SecurityUtils.getCurrentUserId();
        Ctx c = lockForAction(detailId);
        assertOperator(c.detail(), false);
        if (!c.detail().getService().getCategory().is(ServiceCategory.LABORATORY)) {
            throw new AppException(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, "Chỉ xét nghiệm mới có bước lấy mẫu");
        }
        requireDetailStatus(c.detail(), ServiceDetailStatus.IN_PROGRESS, "Phải bắt đầu thực hiện (lấy mẫu) trước khi ghi nhận đã lấy mẫu");
        requireVisitWaitingResults(c.visit());
        if (latestActions(List.of(detailId)).get(detailId) == ServiceDetailAction.SAMPLE_COLLECTED) {
            throw new AppException(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, "Đã ghi nhận lấy mẫu cho chỉ định này rồi");
        }
        // Trạng thái vẫn IN_PROGRESS; "đang phân tích" suy ra từ action mới nhất
        support.recordDetail(detailId, ServiceDetailStatus.IN_PROGRESS, ServiceDetailStatus.IN_PROGRESS, ServiceDetailAction.SAMPLE_COLLECTED,
                actor, null);
        return assembler.toServiceDetail(c.detail());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ServiceDetailResponse perform(UUID detailId) {
        UUID actor = SecurityUtils.getCurrentUserId();
        Ctx c = lockForAction(detailId);
        assertOperator(c.detail(), true);
        if (!c.detail().getService().getCategory().isInRoom()) {
            throw new AppException(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, "Chỉ dịch vụ thực hiện tại phòng mới bấm \"Đã thực hiện\"");
        }
        support.assertAssignedTo(c.visit(), actor);
        support.assertStatus(c.visit(), VisitStatus.IN_PROGRESS, "Chỉ ghi nhận dịch vụ khi ca đang khám (IN_PROGRESS)");
        requireDetailStatus(c.detail(), ServiceDetailStatus.PENDING, "Chỉ ghi nhận \"Đã thực hiện\" cho dịch vụ đang chờ (PENDING)");
        setStatus(c.detail(), ServiceDetailStatus.IN_PROGRESS, ServiceDetailAction.START_PROCESSING, actor, null);
        setStatus(c.detail(), ServiceDetailStatus.COMPLETED, ServiceDetailAction.COMPLETED, actor, "Thực hiện tại phòng khám");
        return assembler.toServiceDetail(c.detail());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ServiceDetailResponse cancel(UUID detailId, String reason, String reasonType) {
        UUID actor = SecurityUtils.getCurrentUserId();
        Ctx c = lockForAction(detailId);
        assertOperator(c.detail(), true);
        ServiceCategory category = c.detail().getService().getCategory();
        if (category.is(ServiceCategory.EXAMINATION)) {
            throw new AppException(ErrorCode.INVALID_SERVICE_DETAIL_STATUS,
                    "Không hủy riêng phí khám: dùng \"Hủy ca / Chuyển viện\" khi khách không tiếp tục khám");
        }
        if (!category.isParaclinical()) {
            // Dịch vụ tại phòng: chỉ Bác sĩ được gán, khi ca đang khám và chưa bị khóa (chưa thu tiền nên không có hoàn tiền)
            support.assertAssignedTo(c.visit(), actor);
            support.assertStatus(c.visit(), VisitStatus.IN_PROGRESS, "Chỉ hủy được dịch vụ tại phòng khi ca đang khám (IN_PROGRESS)");
        }
        if (!ServiceDetailStatus.OPEN.contains(c.detail().getStatus())) {
            throw new AppException(ErrorCode.INVALID_SERVICE_DETAIL_STATUS,
                    "Chỉ hủy được chỉ định đang chờ hoặc đang thực hiện");
        }
        boolean refund = category.isParaclinical() && REASON_CLINIC.equals(resolveReasonType(reasonType));
        setStatus(c.detail(), ServiceDetailStatus.CANCELLED, ServiceDetailAction.CANCELLED, actor, reason.trim());
        // BR-33 / EX-08: chưa thu -> CANCELLED; đã thu + lỗi phòng khám -> REFUNDED; đã thu + khách tự hủy -> giữ PAID
        support.settleInvoiceOfCancelled(c.detail(), refund);
        queue.refreshRoomTicketOf(c.visit().getId(), detailId);   // phiếu phòng không còn dịch vụ chờ -> đóng
        // EX-08: dịch vụ này là dịch vụ cuối còn dở dang thì ca chuyển sang RESULTS_READY với kết quả đã có
        support.evaluateParaclinicalCompletion(c.visit(), actor);
        return assembler.toServiceDetail(c.detail());
    }

    /**
     * Ai chịu lỗi khi hủy dịch vụ XN / CĐHA đã thu tiền: CLINIC (thiết bị hỏng, không thực hiện được) thì hoàn tiền, CUSTOMER (khách
     * tự hủy / bỏ về) thì không. Không gửi thì mặc định theo vai trò: Bác sĩ đổi hướng xử lý = CLINIC, kỹ thuật viên = CUSTOMER.
     */
    private static String resolveReasonType(String reasonType) {
        if (reasonType != null && !reasonType.isBlank()) {
            return reasonType.trim().toUpperCase();
        }
        return SecurityUtils.getCurrentRole().orElse(null) == RoleCode.VETERINARIAN ? REASON_CLINIC : REASON_CUSTOMER;
    }

    // ================================================================== nhập kết quả

    @Override
    @Transactional(readOnly = true)
    public List<LabTestItemResponse> labForm(UUID detailId) {
        VisitServiceDetail detail = detailRepository.findById(detailId)
                .orElseThrow(() -> new AppException(ErrorCode.SERVICE_DETAIL_NOT_FOUND));
        assertOperator(detail, true);
        if (!detail.getService().getCategory().is(ServiceCategory.LABORATORY)) {
            throw new AppException(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, "Chỉ định này không phải xét nghiệm");
        }
        PetVisit visit = visitRepository.findWithPetById(detail.getVisitId())
                .orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
        Short speciesId = visit.getPet().getSpecies().getId();
        Set<Short> configured = new HashSet<>(serviceLabItemRepository.findItemIdsByServiceId(detail.getService().getId()));
        List<LabTestItem> items = labTestItemRepository.findAllByOrderByCodeAsc().stream()
                .filter(i -> configured.isEmpty() || configured.contains(i.getId())).toList();
        Map<Short, List<ReferenceRangeResponse>> ranges = items.isEmpty() ? Map.of()
                : rangeRepository.findByLabTestItemIdIn(items.stream().map(LabTestItem::getId).toList()).stream()
                .filter(r -> r.getSpecies().getId().equals(speciesId))   // BR-23: chỉ khoảng của loài thú cưng này
                .collect(Collectors.groupingBy(r -> r.getLabTestItem().getId(), Collectors.mapping(
                        r -> new ReferenceRangeResponse(r.getSpecies().getId(), r.getSpecies().getCode(), r.getMinValue(),
                                r.getMaxValue()), Collectors.toList())));
        return items.stream().map(i -> new LabTestItemResponse(i.getId(), i.getCode(), i.getName(), i.getUnit(),
                i.getDescription(), i.isActive(), ranges.getOrDefault(i.getId(), List.of()))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabResultResponse> labResults(UUID detailId) {
        VisitServiceDetail detail = detailRepository.findById(detailId)
                .orElseThrow(() -> new AppException(ErrorCode.SERVICE_DETAIL_NOT_FOUND));
        if (!detail.getService().getCategory().is(ServiceCategory.LABORATORY)) {
            throw new AppException(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, "Chỉ định này không phải xét nghiệm");
        }
        return assembler.labResultsOf(detailId);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ServiceDetailResponse submitLabResults(UUID detailId, LabResultRequest request) {
        UUID actor = SecurityUtils.getCurrentUserId();
        Ctx c = lockForAction(detailId);
        assertOperator(c.detail(), false);
        if (!c.detail().getService().getCategory().is(ServiceCategory.LABORATORY)) {
            throw new AppException(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, "Chỉ định này không phải xét nghiệm");
        }
        requireDetailStatus(c.detail(), ServiceDetailStatus.IN_PROGRESS,
                "Phải bắt đầu thực hiện (IN_PROGRESS) trước khi nhập kết quả");
        requireVisitWaitingResults(c.visit());

        List<Short> itemIds = request.results().stream().map(LabResultItemRequest::labTestItemId).toList();
        if (new HashSet<>(itemIds).size() != itemIds.size()) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mỗi chỉ số chỉ được nhập một lần");
        }
        Map<Short, LabTestItem> items = labTestItemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(LabTestItem::getId, i -> i));
        // Chỉ nhập các chỉ số thuộc dịch vụ này (chưa cấu hình dòng nào = mọi chỉ số đều được)
        Set<Short> allowed = new HashSet<>(serviceLabItemRepository.findItemIdsByServiceId(c.detail().getService().getId()));
        // BR-23: khoảng tham chiếu phụ thuộc loài của thú cưng
        Short speciesId = c.visit().getPet().getSpecies().getId();

        List<LabTestResult> results = new ArrayList<>();
        for (LabResultItemRequest item : request.results()) {
            LabTestItem labItem = items.get(item.labTestItemId());
            if (labItem == null) {
                throw new AppException(ErrorCode.LAB_ITEM_NOT_FOUND);
            }
            if (!allowed.isEmpty() && !allowed.contains(labItem.getId())) {
                throw new AppException(ErrorCode.LAB_ITEM_NOT_IN_SERVICE, "Chỉ số " + labItem.getCode()
                        + " không thuộc dịch vụ " + c.detail().getService().getName());
            }
            LabTestReferenceRange range = rangeRepository.findByLabTestItemIdAndSpeciesId(labItem.getId(), speciesId)
                    .orElseThrow(() -> new AppException(ErrorCode.LAB_REFERENCE_NOT_FOUND,
                            "Chưa cấu hình khoảng tham chiếu của chỉ số " + labItem.getCode() + " cho loài của thú cưng"));
            results.add(LabTestResult.builder()
                    .visitServiceDetailId(detailId)
                    .labTestItem(labItem)
                    .resultValue(item.resultValue())
                    // BR-24: snapshot khoảng tham chiếu để kết quả cũ không đổi nếu Admin chỉnh cấu hình về sau
                    .minValueSnapshot(range.getMinValue())
                    .maxValueSnapshot(range.getMaxValue())
                    .performedBy(actor)
                    .notes(blankToNull(item.notes()))
                    .build());
        }
        // Gói xét nghiệm có chỉ số nào thì phải nhập đủ chỉ số đó (dịch vụ chưa cấu hình gói thì không áp dụng)
        Set<Short> missing = new HashSet<>(allowed);
        missing.removeAll(items.keySet());
        if (!missing.isEmpty()) {
            String codes = labTestItemRepository.findAllById(missing).stream().map(LabTestItem::getCode).sorted()
                    .collect(Collectors.joining(", "));
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Phải nhập đủ các chỉ số của gói xét nghiệm, còn thiếu: "
                    + (codes.isEmpty() ? missing.toString() : codes));
        }
        labResultRepository.saveAll(results);

        setStatus(c.detail(), ServiceDetailStatus.COMPLETED, ServiceDetailAction.COMPLETED, actor, null);
        queue.refreshRoomTicketOf(c.visit().getId(), detailId);   // mọi dịch vụ của phiếu đã xong -> phiếu DONE
        support.evaluateParaclinicalCompletion(c.visit(), actor);
        return assembler.toServiceDetail(c.detail());
    }

    /**
     * Không @Transactional ở mức method: tệp được lưu ra đĩa TRƯỚC, rồi mới ghi CSDL trong transaction; nếu ghi CSDL
     * thất bại thì xóa các tệp vừa lưu để không để lại tệp mồ côi.
     */
    @Override
    public ServiceDetailResponse submitImagingResult(UUID detailId, String description, String conclusion,
                                                     List<MultipartFile> files) {
        UUID actor = SecurityUtils.getCurrentUserId();
        if (SecurityUtils.getCurrentRole().orElse(null) != RoleCode.IMAGING_TECHNICIAN) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        if (blankToNull(description) == null || blankToNull(conclusion) == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Phải nhập mô tả hình ảnh và kết luận");
        }
        if (files == null || files.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_IMAGING_FILE, "Phải đính kèm ít nhất một ảnh");
        }
        if (files.size() > MAX_FILES) {
            throw new AppException(ErrorCode.INVALID_IMAGING_FILE, "Tối đa " + MAX_FILES + " ảnh mỗi kết quả");
        }
        files.forEach(storage::validate); // kiểm tra hết trước khi ghi bất kỳ tệp nào
        VisitServiceDetail preview = detailRepository.findById(detailId)
                .orElseThrow(() -> new AppException(ErrorCode.SERVICE_DETAIL_NOT_FOUND));

        List<ImagingStorage.StoredFile> stored = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                stored.add(storage.store(file));
            }
            return readCommittedTemplate.execute(tx -> saveImaging(actor, detailId, description.trim(),
                    conclusion.trim(), stored));
        } catch (RuntimeException e) {
            stored.forEach(f -> storage.deleteQuietly(f.fileKey()));
            throw e;
        }
    }

    private ServiceDetailResponse saveImaging(UUID actor, UUID detailId, String description, String conclusion,
                                              List<ImagingStorage.StoredFile> stored) {
        Ctx c = lockForAction(detailId);
        assertOperator(c.detail(), false);
        if (!c.detail().getService().getCategory().is(ServiceCategory.IMAGING)) {
            throw new AppException(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, "Chỉ định này không phải chẩn đoán hình ảnh");
        }
        requireDetailStatus(c.detail(), ServiceDetailStatus.IN_PROGRESS,
                "Phải bắt đầu thực hiện (IN_PROGRESS) trước khi nhập kết quả");
        requireVisitWaitingResults(c.visit());

        ImagingResult result = imagingResultRepository.saveAndFlush(ImagingResult.builder()
                .visitServiceDetailId(detailId).description(description).conclusion(conclusion).performedBy(actor).build());
        attachmentRepository.saveAll(stored.stream().map(f -> ImagingAttachment.builder()
                .imagingResultId(result.getId()).fileKey(f.fileKey()).originalFileName(f.originalName())
                .fileSize(f.size()).contentType(f.contentType()).uploadedBy(actor).build()).toList());

        setStatus(c.detail(), ServiceDetailStatus.COMPLETED, ServiceDetailAction.COMPLETED, actor, null);
        queue.refreshRoomTicketOf(c.visit().getId(), detailId);   // mọi dịch vụ của phiếu đã xong -> phiếu DONE
        support.evaluateParaclinicalCompletion(c.visit(), actor);
        return assembler.toServiceDetail(c.detail());
    }

    @Override
    @Transactional(readOnly = true)
    public FileUrlSigner.SignedUrl attachmentUrl(UUID attachmentId) {
        UUID actor = SecurityUtils.getCurrentUserId();
        RoleCode role = SecurityUtils.getCurrentRole().orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        ImagingAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        ImagingResult result = imagingResultRepository.findById(attachment.getImagingResultId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        VisitServiceDetail detail = detailRepository.findById(result.getVisitServiceDetailId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        PetVisit visit = visitRepository.findWithPetById(detail.getVisitId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        // Chủ nuôi chỉ tải được ảnh của thú cưng mình; của người khác trả "không tìm thấy"
        if (role == RoleCode.PET_OWNER && !visit.getPet().getOwnerId().equals(actor)) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return urlSigner.sign(attachment.getFileKey());
    }

    // ================================================================== helper

    /** Khóa chỉ định rồi khóa đợt khám (luôn theo thứ tự này). Câu truy vấn khóa là lệnh đầu tiên của transaction. */
    private Ctx lockForAction(UUID detailId) {
        VisitServiceDetail detail = detailRepository.findByIdForUpdate(detailId)
                .orElseThrow(() -> new AppException(ErrorCode.SERVICE_DETAIL_NOT_FOUND));
        PetVisit visit = support.lock(detail.getVisitId());
        return new Ctx(detail, visit);
    }

    /** Kỹ thuật viên chỉ thao tác phòng của mình; Bác sĩ chỉ được hủy chỉ định (đổi hướng xử lý). */
    private void assertOperator(VisitServiceDetail detail, boolean allowDoctor) {
        RoleCode role = SecurityUtils.getCurrentRole().orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        ServiceCategory category = detail.getService().getCategory();
        boolean allowed = (category.is(ServiceCategory.LABORATORY) && role == RoleCode.LAB_TECHNICIAN)
                || (category.is(ServiceCategory.IMAGING) && role == RoleCode.IMAGING_TECHNICIAN)
                || (allowDoctor && role == RoleCode.VETERINARIAN);
        if (!allowed) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
    }

    private void setStatus(VisitServiceDetail detail, ServiceDetailStatus to, ServiceDetailAction action, UUID actor, String reason) {
        ServiceDetailStatus from = detail.getStatus();
        detail.setStatus(to);
        detailRepository.save(detail);
        support.recordDetail(detail.getId(), from, to, action, actor, reason);
    }

    private void requireDetailStatus(VisitServiceDetail detail, ServiceDetailStatus expected, String message) {
        if (detail.getStatus() != expected) {
            throw new AppException(ErrorCode.INVALID_SERVICE_DETAIL_STATUS, message);
        }
    }

    private void requireVisitWaitingResults(PetVisit visit) {
        if (visit.getStatus() != VisitStatus.WAITING_RESULTS) {
            throw new AppException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Đợt khám không còn ở trạng thái chờ kết quả cận lâm sàng");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
