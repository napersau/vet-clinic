package com.vetclinic.clinical.service;

import java.util.UUID;
import com.vetclinic.clinical.constant.LabEvaluation;
import com.vetclinic.clinical.constant.QueueType;
import com.vetclinic.clinical.dto.response.AttachmentResponse;
import com.vetclinic.clinical.dto.response.ImagingResultResponse;
import com.vetclinic.clinical.dto.response.LabResultResponse;
import com.vetclinic.clinical.dto.response.MedicalRecordResponse;
import com.vetclinic.clinical.dto.response.ServiceDetailResponse;
import com.vetclinic.clinical.dto.response.TicketInfo;
import com.vetclinic.clinical.queue.QueueCoordinator;
import com.vetclinic.clinical.queue.TicketView;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.dto.response.VitalSignResponse;
import com.vetclinic.clinical.entity.ImagingAttachment;
import com.vetclinic.clinical.entity.ImagingResult;
import com.vetclinic.clinical.entity.LabTestResult;
import com.vetclinic.clinical.entity.Pet;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.ServiceInvoice;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.ImagingAttachmentRepository;
import com.vetclinic.clinical.repository.ImagingResultRepository;
import com.vetclinic.clinical.repository.LabTestResultRepository;
import com.vetclinic.clinical.repository.MedicalRecordRepository;
import com.vetclinic.clinical.repository.ServiceInvoiceRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import com.vetclinic.clinical.repository.VitalSignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dựng VisitResponse. Phải gọi bên trong transaction (hoặc với entity đã nạp kèm pet và loài) vì open-in-view đã tắt.
 * <p>
 * Lược đồ v3.0 bỏ nhiều cột khỏi pet_visits / visit_service_details / lab_test_results; hình dạng response giữ nguyên bằng
 * cách SUY RA: mốc thời gian và lý do hủy từ lịch sử trạng thái ({@link VisitTimeline}), đơn giá / VAT từ hóa đơn dịch vụ,
 * nhãn đánh giá xét nghiệm từ giá trị so với khoảng tham chiếu đã snapshot. Số phòng do VisitEnricher gắn ở tầng controller.
 */
@Component
@RequiredArgsConstructor
public class VisitAssembler {

    private final VisitServiceDetailRepository detailRepository;
    private final LabTestResultRepository labResultRepository;
    private final ImagingResultRepository imagingResultRepository;
    private final ImagingAttachmentRepository attachmentRepository;
    private final ServiceInvoiceRepository invoiceRepository;
    private final VisitTimeline timeline;
    private final QueueCoordinator queue;
    private final VitalSignRepository vitalSignRepository;
    private final MedicalRecordRepository medicalRecordRepository;

    /** Dùng cho API danh sách: không kèm dịch vụ và kết quả. */
    public VisitResponse summary(PetVisit visit) {
        return build(visit, timeline.of(visit), null, null, null);
    }

    /** Một truy vấn lịch sử cho cả danh sách (không N+1). */
    public List<VisitResponse> summaries(Collection<PetVisit> visits) {
        Map<UUID, VisitTimeline.Times> times = timeline.ofAll(visits);
        return visits.stream()
                .map(v -> build(v, times.getOrDefault(v.getId(), VisitTimeline.Times.EMPTY), null, null, null))
                .toList();
    }

    /** Chi tiết đầy đủ: dịch vụ đã chỉ định, kết quả xét nghiệm (có đánh giá LOW / NORMAL / HIGH) và ảnh CĐHA. */
    public VisitResponse detail(PetVisit visit) {
        List<VisitServiceDetail> details = detailRepository.findByVisitIdOrderByIdAsc(visit.getId());
        List<UUID> detailIds = details.stream().map(VisitServiceDetail::getId).toList();
        Map<UUID, ServiceInvoice> invoices = invoicesOf(detailIds);
        List<LabResultResponse> labs = detailIds.isEmpty() ? List.of()
                : labResultRepository.findByVisitServiceDetailIdInOrderByIdAsc(detailIds).stream().map(this::toLab).toList();
        List<ImagingResult> imagingResults = detailIds.isEmpty() ? List.of()
                : imagingResultRepository.findByVisitServiceDetailIdIn(detailIds);
        Map<UUID, List<ImagingAttachment>> attachments = imagingResults.isEmpty() ? Map.of()
                : attachmentRepository.findByImagingResultIdIn(imagingResults.stream().map(ImagingResult::getId).toList())
                .stream().collect(Collectors.groupingBy(ImagingAttachment::getImagingResultId));
        return build(visit, timeline.of(visit),
                details.stream().map(d -> toServiceDetail(d, invoices.get(d.getId()))).toList(),
                labs,
                imagingResults.stream().map(r -> toImaging(r, attachments.getOrDefault(r.getId(), List.of()))).toList())
                // Phiếu DOCTOR còn hiệu lực (nếu có): giao diện hồ sơ ca hiện số phiếu, Bác sĩ chỉ "Bắt đầu" được khi có phiếu
                .withTicket(doctorTicketOf(visit.getId()))
                .withClinical(vitalSignRepository.findByVisitIdOrderByRecordedAtAscIdAsc(visit.getId()).stream()
                                .map(VitalSignResponse::of).toList(),
                        medicalRecordRepository.findById(visit.getId())
                                .map(r -> MedicalRecordResponse.of(r, isLocked(visit.getStatus()))).orElse(null));
    }

    /** Kết quả xét nghiệm của MỘT chỉ định (kèm nhãn LOW / NORMAL / HIGH tính khi hiển thị). */
    public List<LabResultResponse> labResultsOf(UUID detailId) {
        return labResultRepository.findByVisitServiceDetailIdInOrderByIdAsc(List.of(detailId)).stream().map(this::toLab).toList();
    }

    public ServiceDetailResponse toServiceDetail(VisitServiceDetail d) {
        return toServiceDetail(d, invoiceRepository.findByVisitServiceDetailId(d.getId()).orElse(null));
    }

    private Map<UUID, ServiceInvoice> invoicesOf(List<UUID> detailIds) {
        return detailIds.isEmpty() ? Map.of()
                : invoiceRepository.findByVisitServiceDetailIdIn(detailIds).stream()
                .collect(Collectors.toMap(ServiceInvoice::getVisitServiceDetailId, i -> i));
    }

    private ServiceDetailResponse toServiceDetail(VisitServiceDetail d, ServiceInvoice invoice) {
        BigDecimal unitPrice = invoice == null ? null : invoice.getUnitPrice();
        // Thành tiền trước VAT = số lượng × đơn giá snapshot: tính khi hiển thị, KHÔNG lưu (quy chuẩn: không lưu giá trị dẫn xuất)
        BigDecimal subtotal = unitPrice == null ? null : unitPrice.multiply(BigDecimal.valueOf(d.getQuantity()));
        return new ServiceDetailResponse(d.getId(), d.getVisitId(), d.getService().getId(), d.getService().getCode(),
                d.getService().getName(), d.getService().getCategory().getCode(), d.getService().getCategory().getQueueType(), unitPrice, d.getQuantity(), subtotal, d.getStatus(),
                d.getNotes(), d.getCreatedAt(),
                invoice == null ? null : invoice.getVatRate(),
                invoice == null ? null : invoice.getId(),
                invoice == null ? null : invoice.getInvoiceCode(),
                invoice == null ? null : invoice.getPaymentStatus());
    }

    private LabResultResponse toLab(LabTestResult r) {
        // Không có cột evaluation: LOW / NORMAL / HIGH tính từ giá trị so với khoảng tham chiếu đã snapshot
        LabEvaluation evaluation = LabEvaluation.of(r.getResultValue(), r.getMinValueSnapshot(), r.getMaxValueSnapshot());
        return new LabResultResponse(r.getId(), r.getVisitServiceDetailId(), r.getLabTestItem().getId(),
                r.getLabTestItem().getCode(), r.getLabTestItem().getName(), r.getLabTestItem().getUnit(),
                r.getResultValue(), r.getMinValueSnapshot(), r.getMaxValueSnapshot(), evaluation,
                r.getPerformedBy(), r.getNotes(), r.getCreatedAt());
    }

    private ImagingResultResponse toImaging(ImagingResult r, List<ImagingAttachment> files) {
        List<AttachmentResponse> attachments = files.stream().map(a -> new AttachmentResponse(a.getId(), a.getOriginalFileName(),
                a.getFileSize(), a.getContentType())).toList();
        return new ImagingResultResponse(r.getId(), r.getVisitServiceDetailId(), r.getDescription(), r.getConclusion(),
                r.getPerformedBy(), r.getPerformedAt(), attachments);
    }

    private static boolean isLocked(com.vetclinic.clinical.constant.VisitStatus status) {
        return switch (status) {
            case WAITING_PAYMENT, COMPLETED, CANCELLED -> true;
            default -> false;
        };
    }

    private VisitResponse build(PetVisit v, VisitTimeline.Times times, List<ServiceDetailResponse> services,
                                List<LabResultResponse> labs, List<ImagingResultResponse> imaging) {
        Pet pet = v.getPet();
        // assignedRoom = null ở đây: số phòng là users.room_number của Bác sĩ, VisitEnricher gắn sau commit (không gọi Feign trong transaction)
        return new VisitResponse(v.getId(), v.getVisitCode(), pet.getId(), pet.getName(), pet.getPetCode(),
                pet.getSpecies().getName(), pet.getOwnerId(), v.getAppointmentId(), v.getDoctorId(), v.getDoctorNameSnapshot(), null,
                v.getReceptionistId(), null, v.isEmergency(), v.getStatus(), isLocked(v.getStatus()), null,
                v.getReasonForVisit(), times.cancelReason(), v.getCheckedInAt(), times.startedAt(),
                times.closedAt(), times.completedAt(), services, labs, imaging, null, null);
    }

    /**
     * Phiếu DOCTOR còn hiệu lực lấy từ Redis. Redis chưa sẵn sàng thì BỎ QUA phiếu (null) chứ không làm hỏng việc xem hồ sơ ca: hồ sơ vẫn đọc được
     * từ MySQL khi hàng đợi gặp sự cố (EX-18).
     */
    private TicketInfo doctorTicketOf(java.util.UUID visitId) {
        try {
            return queue.activeDoctorTicket(visitId).map(TicketView::info).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
