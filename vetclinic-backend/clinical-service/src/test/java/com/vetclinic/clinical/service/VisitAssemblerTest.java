package com.vetclinic.clinical.service;

import com.vetclinic.clinical.constant.LabEvaluation;
import com.vetclinic.clinical.constant.PaymentStatus;
import com.vetclinic.clinical.entity.ServiceCategory;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.ClinicService;
import com.vetclinic.clinical.entity.LabTestItem;
import com.vetclinic.clinical.entity.LabTestResult;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.PetVisitStatusHistory;
import com.vetclinic.clinical.entity.ServiceInvoice;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import com.vetclinic.clinical.repository.ImagingAttachmentRepository;
import com.vetclinic.clinical.repository.ImagingResultRepository;
import com.vetclinic.clinical.repository.LabTestResultRepository;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import com.vetclinic.clinical.repository.ServiceInvoiceRepository;
import com.vetclinic.clinical.repository.VisitServiceDetailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.vetclinic.clinical.testsupport.TestData.lid;
import static com.vetclinic.clinical.testsupport.TestData.uid;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.detail;
import static com.vetclinic.clinical.testsupport.TestData.service;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * Lược đồ v3.0 bỏ nhiều cột; VisitAssembler giữ hình dạng response bằng cách SUY RA giá trị (không lưu giá trị dẫn xuất).
 */
@ExtendWith(MockitoExtension.class)
class VisitAssemblerTest {

    @Mock
    private VisitServiceDetailRepository detailRepository;
    @Mock
    private LabTestResultRepository labResultRepository;
    @Mock
    private ImagingResultRepository imagingResultRepository;
    @Mock
    private ImagingAttachmentRepository attachmentRepository;
    @Mock
    private ServiceInvoiceRepository invoiceRepository;
    @Mock
    private PetVisitStatusHistoryRepository historyRepository;
    @Mock
    private com.vetclinic.clinical.repository.VitalSignRepository vitalSignRepository;
    @Mock
    private com.vetclinic.clinical.repository.MedicalRecordRepository medicalRecordRepository;

    private VisitAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new VisitAssembler(detailRepository, labResultRepository, imagingResultRepository, attachmentRepository,
                invoiceRepository, new VisitTimeline(historyRepository),
                Mockito.mock(com.vetclinic.clinical.queue.QueueCoordinator.class), vitalSignRepository, medicalRecordRepository);
    }

    private static PetVisitStatusHistory h(VisitStatus from, VisitStatus to, String at, String notes) {
        return PetVisitStatusHistory.builder().visitId(lid(100L)).fromStatus(from).toStatus(to).notes(notes)
                .createdAt(Instant.parse(at)).build();
    }

    @Test
    void summary_deriveTimesAndCancelReasonFromTheHistory_andExposesQueueFields() {
        PetVisit cancelled = visit(100L, VisitStatus.CANCELLED, DOCTOR);
        when(historyRepository.findByVisitIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                h(null, VisitStatus.WAITING, "2026-10-30T01:00:00Z", null),
                h(VisitStatus.WAITING, VisitStatus.CANCELLED, "2026-10-30T01:05:00Z", "Khách bận")));

        var response = assembler.summary(cancelled);

        assertThat(response.cancelReason()).isEqualTo("Khách bận");
        assertThat(response.closedAt()).isEqualTo(Instant.parse("2026-10-30T01:05:00Z"));
        assertThat(response.ticket()).isNull();   // danh sách không kèm phiếu (chỉ hàng đợi và hồ sơ chi tiết mới gắn)
        assertThat(response.assignedRoom()).isNull();   // do VisitEnricher gắn ở controller
        assertThat(response.locked()).isTrue();
    }

    @Test
    void summaries_loadTheHistoryOfTheWholeListWithOneQuery() {
        PetVisit a = visit(100L, VisitStatus.WAITING, DOCTOR);
        PetVisit b = visit(101L, VisitStatus.WAITING, DOCTOR);
        when(historyRepository.findByVisitIdInOrderByIdAsc(anyCollection())).thenReturn(List.of());

        var list = assembler.summaries(List.of(a, b));

        assertThat(list).hasSize(2);
        Mockito.verify(historyRepository, Mockito.times(1)).findByVisitIdInOrderByIdAsc(anyCollection());
    }

    @Test
    void detail_carriesTheVitalSignsInOrderAndTheMedicalRecord_lockedOnceClosed_whileSummaryDoesNot() {
        PetVisit visit = visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR);
        visit.setDoctorNameSnapshot("BS Tiến");
        visit.setReasonForVisit("Bỏ ăn");
        when(detailRepository.findByVisitIdOrderByIdAsc(lid(100L))).thenReturn(List.of());
        when(historyRepository.findByVisitIdInOrderByIdAsc(anyCollection())).thenReturn(List.of());
        when(vitalSignRepository.findByVisitIdOrderByRecordedAtAscIdAsc(lid(100L))).thenReturn(List.of(
                com.vetclinic.clinical.entity.VitalSign.builder().id(1L).visitId(lid(100L)).weightKg(new BigDecimal("4.60")).build(),
                com.vetclinic.clinical.entity.VitalSign.builder().id(2L).visitId(lid(100L)).temperatureC(new BigDecimal("38.4")).build()));
        when(medicalRecordRepository.findById(lid(100L))).thenReturn(java.util.Optional.of(
                com.vetclinic.clinical.entity.MedicalRecord.builder().visitId(lid(100L)).finalDiagnosis("Viêm da").version(2L).build()));

        var detail = assembler.detail(visit);

        assertThat(detail.doctorName()).isEqualTo("BS Tiến");     // bản chụp trên đợt khám, không gọi auth-service
        assertThat(detail.reasonForVisit()).isEqualTo("Bỏ ăn");
        assertThat(detail.vitalSigns()).extracting(v -> v.id()).containsExactly(1L, 2L);   // mỗi lần đo một dòng, theo thứ tự thời gian
        assertThat(detail.medicalRecord().finalDiagnosis()).isEqualTo("Viêm da");
        assertThat(detail.medicalRecord().locked()).isTrue();      // WAITING_PAYMENT trở đi: khóa
        // API danh sách không kèm sinh hiệu / hồ sơ khám (tránh N+1)
        var summary = assembler.summary(visit);
        assertThat(summary.vitalSigns()).isNull();
        assertThat(summary.medicalRecord()).isNull();
    }

    @Test
    void detail_serviceLinesGetPriceAndVatFromTheirInvoice_notFromTheLine() {
        ClinicService vaccine = service(2L, "VAC_7B", "VACCINATION", "999999.00");   // giá hiện hành đã khác
        VisitServiceDetail line = detail(1L, 100L, vaccine, ServiceDetailStatus.COMPLETED);
        line.setQuantity(2);
        PetVisit visit = visit(100L, VisitStatus.IN_PROGRESS, DOCTOR);
        when(detailRepository.findByVisitIdOrderByIdAsc(lid(100L))).thenReturn(List.of(line));
        when(invoiceRepository.findByVisitServiceDetailIdIn(anyCollection())).thenReturn(List.of(
                ServiceInvoice.builder().id(lid(50L)).invoiceCode("SINV-2026-0050").visitServiceDetailId(lid(1L))
                        .unitPrice(new BigDecimal("150000.00")).vatRate(new BigDecimal("0.05"))
                        .paymentStatus(PaymentStatus.PENDING).build()));
        when(labResultRepository.findByVisitServiceDetailIdInOrderByIdAsc(anyCollection())).thenReturn(List.of());
        when(imagingResultRepository.findByVisitServiceDetailIdIn(anyCollection())).thenReturn(List.of());
        when(historyRepository.findByVisitIdInOrderByIdAsc(anyCollection())).thenReturn(List.of());

        var d = assembler.detail(visit).services().get(0);

        assertThat(d.unitPrice()).isEqualByComparingTo("150000.00");       // snapshot trên hóa đơn, bất biến (BR-25)
        assertThat(d.subtotal()).isEqualByComparingTo("300000.00");        // số lượng × đơn giá, tính khi hiển thị
        assertThat(d.vatRate()).isEqualByComparingTo("0.05");
        assertThat(d.invoiceCode()).isEqualTo("SINV-2026-0050");
        assertThat(d.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void toServiceDetail_withoutInvoice_hasNoPrice() {
        VisitServiceDetail line = detail(1L, 100L, service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00"),
                ServiceDetailStatus.PENDING);
        when(invoiceRepository.findByVisitServiceDetailId(lid(1L))).thenReturn(Optional.empty());

        var d = assembler.toServiceDetail(line);

        assertThat(d.unitPrice()).isNull();
        assertThat(d.invoiceId()).isNull();
    }

    @Test
    void detail_labEvaluationIsComputedFromTheValueAndTheSnapshotRange() {
        ClinicService cbc = service(5L, "LAB_CBC", ServiceCategory.LABORATORY, "250000.00");
        VisitServiceDetail line = detail(1L, 100L, cbc, ServiceDetailStatus.COMPLETED);
        LabTestItem wbc = LabTestItem.builder().id((short) 1).code("WBC").name("WBC").unit("10^9/L").build();
        when(detailRepository.findByVisitIdOrderByIdAsc(lid(100L))).thenReturn(List.of(line));
        when(invoiceRepository.findByVisitServiceDetailIdIn(anyCollection())).thenReturn(List.of());
        when(labResultRepository.findByVisitServiceDetailIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                LabTestResult.builder().id(1L).visitServiceDetailId(lid(1L)).labTestItem(wbc).resultValue(new BigDecimal("21.00"))
                        .minValueSnapshot(new BigDecimal("6.00")).maxValueSnapshot(new BigDecimal("17.00")).performedBy(uid(31)).build(),
                LabTestResult.builder().id(2L).visitServiceDetailId(lid(1L)).labTestItem(wbc).resultValue(new BigDecimal("3.00"))
                        .minValueSnapshot(new BigDecimal("6.00")).maxValueSnapshot(new BigDecimal("17.00")).performedBy(uid(31)).build(),
                LabTestResult.builder().id(3L).visitServiceDetailId(lid(1L)).labTestItem(wbc).resultValue(new BigDecimal("10.00"))
                        .minValueSnapshot(new BigDecimal("6.00")).maxValueSnapshot(new BigDecimal("17.00")).performedBy(uid(31)).build()));
        when(imagingResultRepository.findByVisitServiceDetailIdIn(anyCollection())).thenReturn(List.of());
        when(historyRepository.findByVisitIdInOrderByIdAsc(anyCollection())).thenReturn(List.of());

        var labs = assembler.detail(visit(100L, VisitStatus.RESULTS_READY, DOCTOR)).labResults();

        assertThat(labs).extracting(l -> l.evaluation())
                .containsExactly(LabEvaluation.HIGH, LabEvaluation.LOW, LabEvaluation.NORMAL);
    }
}
