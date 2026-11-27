package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.MedicineClient;
import com.vetclinic.clinical.client.MedicineInvoiceClient;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.request.PrescriptionItemRequest;
import com.vetclinic.clinical.dto.request.PrescriptionRequest;
import com.vetclinic.clinical.dto.response.MedicineInfo;
import com.vetclinic.clinical.dto.response.MedicineInvoiceStatus;
import com.vetclinic.clinical.dto.response.PrescriptionItemResponse;
import com.vetclinic.clinical.dto.response.PrescriptionResponse;
import com.vetclinic.clinical.dto.response.PrescriptionSummary;
import com.vetclinic.clinical.entity.Pet;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.Prescription;
import com.vetclinic.clinical.entity.PrescriptionItem;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PrescriptionRepository;
import com.vetclinic.clinical.service.PrescriptionService;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * KHÔNG @Transactional ở mức class: quy chuẩn cấm gọi Feign trong transaction. Luồng lưu đơn: (1) đọc nhanh (đúng Bác sĩ, ca đang khám, đơn hiện
 * có); (2) gọi billing NGOÀI transaction (thông tin thuốc, hóa đơn thuốc đã PAID chưa); (3) transaction ghi: khóa dòng đợt khám, kiểm tra lại
 * rồi ghi đơn. Khóa dòng đợt khám cũng là thứ chặn chốt ca chen vào giữa.
 */
@Slf4j
@Service
public class PrescriptionServiceImpl implements PrescriptionService {

    private static final String INVOICE_PAID = "PAID";

    private final PrescriptionRepository prescriptionRepository;
    private final PetVisitRepository visitRepository;
    private final VisitSupport support;
    private final MedicineClient medicineClient;
    private final MedicineInvoiceClient invoiceClient;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public PrescriptionServiceImpl(PrescriptionRepository prescriptionRepository, PetVisitRepository visitRepository, VisitSupport support,
                                   MedicineClient medicineClient, MedicineInvoiceClient invoiceClient, PlatformTransactionManager transactionManager) {
        this.prescriptionRepository = prescriptionRepository;
        this.visitRepository = visitRepository;
        this.support = support;
        this.medicineClient = medicineClient;
        this.invoiceClient = invoiceClient;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    /** Kết quả bước đọc nhanh: đơn đã có (nếu có) và tiền sử dị ứng của thú cưng. */
    private record Existing(UUID prescriptionId, String allergies) {
    }

    // ================================================================== kê đơn

    @Override
    public PrescriptionResponse save(UUID visitId, PrescriptionRequest request) {
        UUID doctorId = SecurityUtils.getCurrentUserId();
        Set<UUID> ids = new HashSet<>();
        for (PrescriptionItemRequest r : request.items()) {
            if (!ids.add(r.medicineId())) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                        "Thuốc " + r.medicineId() + " xuất hiện nhiều lần trong đơn, hãy gộp thành một dòng");
            }
        }
        Existing existing = readTx.execute(s -> {
            PetVisit visit = visitRepository.findById(visitId).orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
            assertMayPrescribe(visit, doctorId);
            UUID prescriptionId = prescriptionRepository.findWithItemsByVisitId(visitId).map(Prescription::getId).orElse(null);
            return new Existing(prescriptionId, visit.getPet().getAllergies());
        });

        // ---- ngoài transaction: hỏi billing
        Map<UUID, MedicineInfo> medicines = new LinkedHashMap<>();
        for (UUID medicineId : ids) {
            medicines.put(medicineId, requireMedicine(medicineClient.getMedicine(medicineId)));
        }
        if (existing.prescriptionId() != null) {
            MedicineInvoiceStatus invoice = unwrap(invoiceClient.status(existing.prescriptionId()));
            if (invoice != null && INVOICE_PAID.equals(invoice.status())) {
                throw new AppException(ErrorCode.PRESCRIPTION_LOCKED, "Đơn thuốc đã được khách thanh toán, không thể chỉnh sửa");
            }
        }

        // ---- transaction ghi
        return tx.execute(s -> persist(visitId, doctorId, request, medicines, existing.allergies()));
    }

    private PrescriptionResponse persist(UUID visitId, UUID doctorId, PrescriptionRequest request, Map<UUID, MedicineInfo> medicines, String allergies) {
        PetVisit visit = support.lock(visitId);   // khóa dòng: chốt ca / kê đơn đồng thời được xếp hàng
        assertMayPrescribe(visit, doctorId);
        Prescription prescription = prescriptionRepository.findWithItemsByVisitId(visitId)
                .orElseGet(() -> Prescription.builder().visitId(visitId).build());
        prescription.setDoctorNotes(blankToNull(request.doctorNotes()));
        List<String> stockWarnings = mergeItems(prescription, request.items(), medicines);
        try {
            prescription = prescriptionRepository.saveAndFlush(prescription);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.PRESCRIPTION_ALREADY_EXISTS);   // visit_id UNIQUE là chốt chặn cuối
        }
        return toResponse(prescription, visit, null, null, stockWarnings, allergyWarning(allergies, request.items(), medicines));
    }

    /**
     * Đưa các dòng của đơn về đúng danh sách yêu cầu, ghép theo THUỐC (mỗi thuốc một dòng): dòng đã có thì sửa tại chỗ, thuốc mới thì thêm, thuốc
     * không còn thì bỏ. Không xóa rồi thêm lại cùng thuốc vì Hibernate chạy INSERT trước DELETE sẽ vi phạm UNIQUE(prescription_id, medicine_id).
     * Kê vượt tồn kho chỉ cảnh báo (EX-13), không chặn: khách có thể mua ngoài.
     */
    private List<String> mergeItems(Prescription prescription, List<PrescriptionItemRequest> requests, Map<UUID, MedicineInfo> medicines) {
        Map<UUID, PrescriptionItem> current = new LinkedHashMap<>();
        for (PrescriptionItem item : prescription.getItems()) {
            current.put(item.getMedicineId(), item);
        }
        List<String> warnings = new ArrayList<>();
        for (PrescriptionItemRequest r : requests) {
            MedicineInfo medicine = medicines.get(r.medicineId());
            if (r.quantity() > medicine.stockQuantity()) {
                warnings.add(medicine.name() + ": tồn kho " + medicine.stockQuantity() + " < số lượng kê " + r.quantity());
            }
            PrescriptionItem item = current.remove(r.medicineId());
            if (item == null) {
                item = PrescriptionItem.builder().medicineId(r.medicineId()).build();
                prescription.addItem(item);
            }
            item.setMedicineNameSnapshot(medicine.name());   // bản chụp tên + đơn vị lúc kê
            item.setUnitSnapshot(medicine.baseUnit());
            item.setQuantity(r.quantity());
            item.setDosage(r.dosage().trim());
            item.setFrequency(r.frequency().trim());
            item.setDurationDays(r.durationDays());
            item.setInstructions(blankToNull(r.instructions()));
        }
        prescription.getItems().removeAll(current.values());
        return warnings;
    }

    private static void assertMayPrescribe(PetVisit visit, UUID doctorId) {
        if (visit.getDoctorId() == null || !visit.getDoctorId().equals(doctorId)) {
            throw new AppException(ErrorCode.NOT_ASSIGNED_DOCTOR);
        }
        if (visit.getStatus() != VisitStatus.IN_PROGRESS) {
            throw new AppException(ErrorCode.PRESCRIPTION_LOCKED);
        }
    }

    private static MedicineInfo requireMedicine(ApiResponse<MedicineInfo> response) {
        MedicineInfo medicine = unwrap(response);
        if (medicine == null || !medicine.active()) {
            throw new AppException(ErrorCode.MEDICINE_NOT_FOUND, "Thuốc không có trong danh mục hoặc đã ngừng kinh doanh");
        }
        return medicine;
    }

    private static <T> T unwrap(ApiResponse<T> response) {
        return response == null ? null : response.getData();
    }

    // ================================================================== cảnh báo dị ứng

    /**
     * Cảnh báo (không chặn) khi tên thuốc hoặc một hoạt chất trùng với tiền sử dị ứng ghi trên hồ sơ thú cưng. So khớp không dấu, không phân biệt
     * hoa thường: "Penicillin, sữa" khớp với hoạt chất "penicillin".
     */
    static String allergyWarning(String allergies, List<PrescriptionItemRequest> requests, Map<UUID, MedicineInfo> medicines) {
        if (allergies == null || allergies.isBlank()) {
            return null;
        }
        String normalizedAllergies = normalize(allergies);
        List<String> allergens = new ArrayList<>();
        for (String part : normalizedAllergies.split("[,;/\\n]+")) {
            if (part.trim().length() >= 3) {
                allergens.add(part.trim());
            }
        }
        List<String> hits = new ArrayList<>();
        for (PrescriptionItemRequest r : requests) {
            MedicineInfo medicine = medicines.get(r.medicineId());
            List<String> terms = new ArrayList<>();
            terms.add(medicine.name());
            if (medicine.ingredients() != null) {
                medicine.ingredients().forEach(i -> terms.add(i.ingredientName()));
            }
            for (String term : terms) {
                String n = normalize(term);
                if (n.length() >= 3 && (normalizedAllergies.contains(n) || allergens.stream().anyMatch(n::contains))) {
                    hits.add(medicine.name() + " (" + term + ")");
                    break;
                }
            }
        }
        return hits.isEmpty() ? null
                : "Thú cưng có tiền sử dị ứng \"" + allergies.trim() + "\": hãy kiểm tra lại thuốc " + String.join(", ", hits);
    }

    private static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return decomposed.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT).trim();
    }

    // ================================================================== tra cứu

    @Override
    public PrescriptionResponse getByVisit(UUID visitId) {
        PrescriptionResponse base = readTx.execute(s -> {
            PetVisit visit = visitRepository.findById(visitId).orElseThrow(() -> new AppException(ErrorCode.VISIT_NOT_FOUND));
            authorizeRead(visit);
            Prescription prescription = prescriptionRepository.findWithItemsByVisitId(visitId)
                    .orElseThrow(() -> new AppException(ErrorCode.PRESCRIPTION_NOT_FOUND));
            return toResponse(prescription, visit, null, null, List.of(), null);
        });
        return enrichWithInvoice(base);
    }

    @Override
    public PrescriptionResponse print(UUID visitId) {
        return getByVisit(visitId);
    }

    /** Chủ nuôi chỉ xem được đơn của thú cưng mình; người khác trả NOT_FOUND để không lộ sự tồn tại của hồ sơ. */
    private static void authorizeRead(PetVisit visit) {
        boolean owner = SecurityUtils.getCurrentRole().filter(r -> r == RoleCode.PET_OWNER).isPresent();
        if (owner && !visit.getPet().getOwnerId().equals(SecurityUtils.getCurrentUserId())) {
            throw new AppException(ErrorCode.PRESCRIPTION_NOT_FOUND);
        }
    }

    /** Gắn hóa đơn thuốc nháp của billing (làm giàu, ngoài transaction): billing sập thì để null, không làm hỏng việc xem đơn. */
    private PrescriptionResponse enrichWithInvoice(PrescriptionResponse base) {
        try {
            MedicineInvoiceStatus status = unwrap(invoiceClient.status(base.id()));
            return status == null || status.invoiceId() == null ? base : base.withInvoice(status.invoiceId(), status.status());
        } catch (RuntimeException e) {
            log.warn("Không lấy được hóa đơn thuốc của đơn {} từ billing-pharmacy-service: {}", base.id(), e.getMessage());
            return base;
        }
    }

    @Override
    public Map<UUID, PrescriptionSummary> summariesByVisit(Collection<UUID> visitIds) {
        if (visitIds == null || visitIds.isEmpty()) {
            return Map.of();
        }
        return readTx.execute(s -> prescriptionRepository.findWithItemsByVisitIdIn(visitIds).stream()
                .map(p -> new PrescriptionSummary(p.getId(), p.getVisitId(), p.getDoctorNotes(), p.getItems().stream()
                        .map(i -> new PrescriptionSummary.Item(i.getMedicineId(), i.getMedicineNameSnapshot(), i.getUnitSnapshot(), i.getQuantity(),
                                i.getDosage(), i.getFrequency(), i.getDurationDays(), i.getInstructions()))
                        .toList()))
                .collect(Collectors.toMap(PrescriptionSummary::visitId, Function.identity(), (a, b) -> a)));
    }

    private static PrescriptionResponse toResponse(Prescription prescription, PetVisit visit, UUID invoiceId, String invoiceStatus,
                                                   List<String> stockWarnings, String allergyWarning) {
        Pet pet = visit.getPet();
        List<PrescriptionItemResponse> items = prescription.getItems().stream()
                .map(i -> new PrescriptionItemResponse(i.getId(), i.getMedicineId(), i.getMedicineNameSnapshot(), i.getUnitSnapshot(), i.getQuantity(),
                        i.getDosage(), i.getFrequency(), i.getDurationDays(), i.getInstructions()))
                .toList();
        return new PrescriptionResponse(prescription.getId(), visit.getId(), visit.getVisitCode(), pet.getName(), pet.getOwnerNameSnapshot(),
                visit.getDoctorNameSnapshot(), prescription.getDoctorNotes(), prescription.getCreatedAt(), invoiceId, invoiceStatus, items,
                stockWarnings, allergyWarning);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
