package com.vetclinic.reporting.service;

import com.vetclinic.common.event.MedicineInvoicePaidEvent;
import com.vetclinic.common.event.PetUpdatedEvent;
import com.vetclinic.common.event.ServiceInvoicePaidEvent;
import com.vetclinic.common.event.ServiceInvoiceRefundedEvent;
import com.vetclinic.common.event.UserProfileUpdatedEvent;
import com.vetclinic.common.event.VisitCancelledEvent;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.VisitCompletedEvent;
import com.vetclinic.reporting.config.ReportingProperties;
import com.vetclinic.reporting.constant.RevenueEntryType;
import com.vetclinic.reporting.constant.RevenueType;
import com.vetclinic.reporting.entity.RevenueFact;
import com.vetclinic.reporting.entity.VisitFact;
import com.vetclinic.reporting.repository.DailyRevenueSummaryRepository;
import com.vetclinic.reporting.repository.DoctorDailyPerformanceRepository;
import com.vetclinic.reporting.repository.RevenueFactRepository;
import com.vetclinic.reporting.repository.VisitFactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportingProjectionServiceTest {

    @Mock VisitFactRepository visitFacts;
    @Mock RevenueFactRepository revenueFacts;
    @Mock DailyRevenueSummaryRepository summaries;
    @Mock DoctorDailyPerformanceRepository performances;

    ReportingProjectionService service;

    final UUID visitId = UUID.randomUUID();
    final UUID petId = UUID.randomUUID();
    final UUID ownerId = UUID.randomUUID();
    final UUID doctorId = UUID.randomUUID();
    final UUID cashierId = UUID.randomUUID();
    /** 17:30 UTC ngày 20/11 = 00:30 ngày 21/11 giờ phòng khám (UTC+7): kiểm tra business_date theo giờ VN. */
    final Instant lateEvening = Instant.parse("2026-11-20T17:30:00Z");

    @BeforeEach
    void setUp() {
        service = new ReportingProjectionService(visitFacts, revenueFacts, summaries, performances, new ReportingProperties("Asia/Ho_Chi_Minh"));
        lenient().when(revenueFacts.existsBySourceInvoiceIdAndItemIdAndEntryType(any(), any(), any(RevenueEntryType.class))).thenReturn(false);
    }

    private VisitFact fact(String status, boolean emergency) {
        return VisitFact.builder().visitId(visitId).visitCode("VIS-1").businessDate(LocalDate.of(2026, 11, 21)).checkedInAt(lateEvening)
                .currentStatus(status).emergency(emergency).petId(petId).petName("Lu").speciesCode("CAT").ownerId(ownerId).ownerName("Lan")
                .doctorId(doctorId).doctorName("BS Tiến").build();
    }

    private VisitCheckedInEvent checkedIn() {
        return new VisitCheckedInEvent(UUID.randomUUID(), lateEvening, cashierId, visitId, "VIS-1", petId, null, true, doctorId,
                "Lu", "CAT", ownerId, "Lan", "BS Tiến");
    }

    // ------------------------------------------------------------------ đợt khám

    @Test
    void checkInCreatesFactWithBusinessDateInClinicTimeZone() {
        when(visitFacts.existsById(visitId)).thenReturn(false);

        service.onVisitCheckedIn(checkedIn());

        ArgumentCaptor<VisitFact> captor = ArgumentCaptor.forClass(VisitFact.class);
        verify(visitFacts).save(captor.capture());
        VisitFact saved = captor.getValue();
        assertThat(saved.getBusinessDate()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(saved.getCurrentStatus()).isEqualTo("WAITING");
        assertThat(saved.isEmergency()).isTrue();
        assertThat(saved.getPetName()).isEqualTo("Lu");
        assertThat(saved.getSpeciesCode()).isEqualTo("CAT");
        assertThat(saved.getOwnerName()).isEqualTo("Lan");
        assertThat(saved.getDoctorName()).isEqualTo("BS Tiến");
    }

    @Test
    void checkInOldFormatEventStillWritesRowWithPlaceholders() {
        when(visitFacts.existsById(visitId)).thenReturn(false);
        var old = new VisitCheckedInEvent(UUID.randomUUID(), lateEvening, cashierId, visitId, "VIS-1", petId, null, false, null);

        service.onVisitCheckedIn(old);

        ArgumentCaptor<VisitFact> captor = ArgumentCaptor.forClass(VisitFact.class);
        verify(visitFacts).save(captor.capture());
        assertThat(captor.getValue().getPetName()).isEqualTo(ReportingProjectionService.UNKNOWN);
        assertThat(captor.getValue().getOwnerId()).isEqualTo(ReportingProjectionService.NIL_UUID);
        assertThat(captor.getValue().getDoctorId()).isNull();
    }

    @Test
    void checkInDuplicateKeepsExistingRow() {
        when(visitFacts.existsById(visitId)).thenReturn(true);

        service.onVisitCheckedIn(checkedIn());

        verify(visitFacts, never()).save(any());
    }

    @Test
    void completedMarksFactAndCountsDoctorEmergencyVisit() {
        VisitFact fact = fact("WAITING", true);
        when(visitFacts.findById(visitId)).thenReturn(Optional.of(fact));

        service.onVisitCompleted(new VisitCompletedEvent(UUID.randomUUID(), lateEvening, cashierId, visitId, "VIS-1", petId, false, doctorId, "BS Tiến"));

        assertThat(fact.getCurrentStatus()).isEqualTo("COMPLETED");
        assertThat(fact.getCompletedAt()).isEqualTo(lateEvening);
        verify(performances).accumulate(eq(LocalDate.of(2026, 11, 21)), any(byte[].class), eq("BS Tiến"), eq(1), eq(0), eq(1), eq(BigDecimal.ZERO));
    }

    @Test
    void completedTwiceCountsOnce() {
        when(visitFacts.findById(visitId)).thenReturn(Optional.of(fact("COMPLETED", false)));

        service.onVisitCompleted(new VisitCompletedEvent(UUID.randomUUID(), lateEvening, cashierId, visitId, "VIS-1", petId, false));

        verify(performances, never()).accumulate(any(), any(), anyString(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void completedBeforeCheckInThrowsSoRabbitRetries() {
        when(visitFacts.findById(visitId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.onVisitCompleted(new VisitCompletedEvent(UUID.randomUUID(), lateEvening, cashierId, visitId, "VIS-1", petId, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelledMarksFactAndCountsCancelledVisit() {
        VisitFact fact = fact("WAITING", false);
        when(visitFacts.findById(visitId)).thenReturn(Optional.of(fact));

        service.onVisitCancelled(new VisitCancelledEvent(UUID.randomUUID(), lateEvening, cashierId, visitId, "VIS-1", petId, doctorId, "Khách bỏ về"));

        assertThat(fact.getCurrentStatus()).isEqualTo("CANCELLED");
        verify(performances).accumulate(eq(LocalDate.of(2026, 11, 21)), any(byte[].class), eq("BS Tiến"), eq(0), eq(1), eq(0), eq(BigDecimal.ZERO));
    }

    // ------------------------------------------------------------------ doanh thu dịch vụ

    private ServiceInvoicePaidEvent paid(UUID invoiceId, UUID serviceId) {
        // 1 x 250.000, VAT 0, giảm 50.000 => thu 200.000; phí khám 100.000 VAT 5% => 105.000
        var xray = new ServiceInvoicePaidEvent.Line(invoiceId, "SINV-1", UUID.randomUUID(), serviceId, "XRAY", "Chụp X-quang", "IMAGING", "Chẩn đoán hình ảnh",
                "IMAGING", 1, new BigDecimal("250000.00"), new BigDecimal("0.00"), new BigDecimal("50000.00"), new BigDecimal("200000.00"));
        return new ServiceInvoicePaidEvent(UUID.randomUUID(), lateEvening, cashierId, visitId, "VIS-1", petId, "Lu", "Lan", doctorId, false, cashierId,
                lateEvening, List.of(xray));
    }

    @Test
    void servicePaidWritesSaleRowSummaryAndDoctorRevenue() {
        when(visitFacts.findById(visitId)).thenReturn(Optional.of(fact("WAITING", false)));
        UUID invoiceId = UUID.randomUUID();

        service.onServiceInvoicePaid(paid(invoiceId, UUID.randomUUID()));

        ArgumentCaptor<RevenueFact> captor = ArgumentCaptor.forClass(RevenueFact.class);
        verify(revenueFacts).save(captor.capture());
        RevenueFact row = captor.getValue();
        assertThat(row.getRevenueType()).isEqualTo(RevenueType.SERVICE);
        assertThat(row.getEntryType()).isEqualTo(RevenueEntryType.SALE);
        assertThat(row.getBusinessDate()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(row.getAmountBeforeVat()).isEqualByComparingTo("250000.00");
        assertThat(row.getVatAmount()).isEqualByComparingTo("0.00");
        assertThat(row.getDiscountAmount()).isEqualByComparingTo("50000.00");
        assertThat(row.getNetAmount()).isEqualByComparingTo("200000.00");
        assertThat(row.getCategoryName()).isEqualTo("Chẩn đoán hình ảnh");
        assertThat(row.getDoctorName()).isEqualTo("BS Tiến");
        verify(summaries).accumulate(LocalDate.of(2026, 11, 21), "SERVICE", "IMAGING", "Chẩn đoán hình ảnh", 1,
                new BigDecimal("250000.00"), new BigDecimal("0.00"), new BigDecimal("50000.00"), new BigDecimal("200000.00"));
        verify(performances).accumulate(eq(LocalDate.of(2026, 11, 21)), any(byte[].class), eq("BS Tiến"), eq(0), eq(0), eq(0), eq(new BigDecimal("200000.00")));
    }

    @Test
    void servicePaidVatIsDerivedSoThatBeforePlusVatMinusDiscountEqualsNet() {
        when(visitFacts.findById(visitId)).thenReturn(Optional.empty());
        var line = new ServiceInvoicePaidEvent.Line(UUID.randomUUID(), "SINV-2", UUID.randomUUID(), UUID.randomUUID(), "EXAM_GEN", "Khám", "EXAMINATION",
                "EXAMINATION", 1, new BigDecimal("100000.00"), new BigDecimal("0.05"), BigDecimal.ZERO, new BigDecimal("105000.00"));
        // dạng cũ (không có tên nhóm): dùng mã nhóm làm tên
        var oldLine = new ServiceInvoicePaidEvent.Line(line.invoiceId(), "SINV-2", line.visitServiceDetailId(), line.serviceId(), "EXAM_GEN", "Khám",
                "EXAMINATION", "EXAMINATION", 1, new BigDecimal("100000.00"), new BigDecimal("0.05"), BigDecimal.ZERO, new BigDecimal("105000.00"));

        service.onServiceInvoicePaid(new ServiceInvoicePaidEvent(UUID.randomUUID(), lateEvening, cashierId, visitId, "VIS-1", petId, "Lu", "Lan", null, false,
                cashierId, null, List.of(oldLine)));

        ArgumentCaptor<RevenueFact> captor = ArgumentCaptor.forClass(RevenueFact.class);
        verify(revenueFacts).save(captor.capture());
        assertThat(captor.getValue().getVatAmount()).isEqualByComparingTo("5000.00");
        assertThat(captor.getValue().getCategoryName()).isEqualTo("EXAMINATION");
        assertThat(captor.getValue().getDoctorId()).isNull();
        verify(performances, never()).accumulate(any(), any(), anyString(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void servicePaidUsesDoctorNameFromTheEventEvenWhenCheckInHasNotBeenProcessedYet() {
        when(visitFacts.findById(visitId)).thenReturn(Optional.empty());   // queue tiếp đón chậm hơn queue thu tiền
        var line = new ServiceInvoicePaidEvent.Line(UUID.randomUUID(), "SINV-3", UUID.randomUUID(), UUID.randomUUID(), "EXAM_GEN", "Khám", "EXAMINATION",
                "Khám bệnh", "EXAMINATION", 1, new BigDecimal("100000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100000.00"));

        service.onServiceInvoicePaid(new ServiceInvoicePaidEvent(UUID.randomUUID(), lateEvening, cashierId, visitId, "VIS-1", petId, "Lu", "Lan", doctorId, "BS Tiến",
                false, cashierId, lateEvening, List.of(line)));

        ArgumentCaptor<RevenueFact> captor = ArgumentCaptor.forClass(RevenueFact.class);
        verify(revenueFacts).save(captor.capture());
        assertThat(captor.getValue().getDoctorName()).isEqualTo("BS Tiến");
        verify(performances).accumulate(eq(LocalDate.of(2026, 11, 21)), any(byte[].class), eq("BS Tiến"), eq(0), eq(0), eq(0), eq(new BigDecimal("100000.00")));
    }

    @Test
    void servicePaidSkipsLinesAlreadyRecorded() {
        UUID invoiceId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        when(visitFacts.findById(visitId)).thenReturn(Optional.empty());
        when(revenueFacts.existsBySourceInvoiceIdAndItemIdAndEntryType(invoiceId, serviceId, RevenueEntryType.SALE)).thenReturn(true);

        service.onServiceInvoicePaid(paid(invoiceId, serviceId));

        verify(revenueFacts, never()).save(any());
        verify(summaries, never()).accumulate(any(), anyString(), anyString(), anyString(), anyInt(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ hoàn tiền

    private RevenueFact sale(UUID invoiceId, UUID itemId) {
        return RevenueFact.builder().revenueType(RevenueType.SERVICE).entryType(RevenueEntryType.SALE).sourceInvoiceId(invoiceId).invoiceCode("SINV-1").itemId(itemId).itemCode("XRAY")
                .itemName("Chụp X-quang").categoryCode("IMAGING").categoryName("Chẩn đoán hình ảnh").visitId(visitId).doctorId(doctorId).doctorName("BS Tiến")
                .businessDate(LocalDate.of(2026, 11, 20)).occurredAt(lateEvening.minusSeconds(86400)).quantity(1)
                .amountBeforeVat(new BigDecimal("250000.00")).vatAmount(new BigDecimal("0.00")).discountAmount(new BigDecimal("50000.00"))
                .netAmount(new BigDecimal("200000.00")).build();
    }

    private ServiceInvoiceRefundedEvent refund(UUID invoiceId) {
        return new ServiceInvoiceRefundedEvent(UUID.randomUUID(), lateEvening, cashierId, visitId, "VIS-1", invoiceId, "SINV-1", UUID.randomUUID(),
                UUID.randomUUID(), "Chụp X-quang", "IMAGING", new BigDecimal("200000.00"), "Phòng khám không thực hiện được");
    }

    @Test
    void refundWritesNegativeCopyOfSaleOnRefundDate() {
        UUID invoiceId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        when(revenueFacts.findBySourceInvoiceIdAndRevenueTypeAndEntryType(invoiceId, RevenueType.SERVICE, RevenueEntryType.SALE)).thenReturn(List.of(sale(invoiceId, itemId)));

        service.onServiceInvoiceRefunded(refund(invoiceId));

        ArgumentCaptor<RevenueFact> captor = ArgumentCaptor.forClass(RevenueFact.class);
        verify(revenueFacts).save(captor.capture());
        RevenueFact row = captor.getValue();
        assertThat(row.getEntryType()).isEqualTo(RevenueEntryType.REFUND);
        assertThat(row.getQuantity()).isEqualTo(-1);
        assertThat(row.getNetAmount()).isEqualByComparingTo("-200000.00");
        assertThat(row.getAmountBeforeVat()).isEqualByComparingTo("-250000.00");
        assertThat(row.getDiscountAmount()).isEqualByComparingTo("-50000.00");
        assertThat(row.getBusinessDate()).isEqualTo(LocalDate.of(2026, 11, 21));
        verify(summaries).accumulate(LocalDate.of(2026, 11, 21), "SERVICE", "IMAGING", "Chẩn đoán hình ảnh", -1,
                new BigDecimal("-250000.00"), new BigDecimal("0.00"), new BigDecimal("-50000.00"), new BigDecimal("-200000.00"));
        verify(performances).accumulate(eq(LocalDate.of(2026, 11, 21)), any(byte[].class), eq("BS Tiến"), eq(0), eq(0), eq(0), eq(new BigDecimal("-200000.00")));
    }

    @Test
    void refundWithoutSaleRowThrowsSoRabbitRetries() {
        UUID invoiceId = UUID.randomUUID();
        when(revenueFacts.findBySourceInvoiceIdAndRevenueTypeAndEntryType(invoiceId, RevenueType.SERVICE, RevenueEntryType.SALE)).thenReturn(List.of());

        assertThatThrownBy(() -> service.onServiceInvoiceRefunded(refund(invoiceId))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refundAlreadyRecordedIsSkipped() {
        UUID invoiceId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        when(revenueFacts.findBySourceInvoiceIdAndRevenueTypeAndEntryType(invoiceId, RevenueType.SERVICE, RevenueEntryType.SALE)).thenReturn(List.of(sale(invoiceId, itemId)));
        when(revenueFacts.existsBySourceInvoiceIdAndItemIdAndEntryType(invoiceId, itemId, RevenueEntryType.REFUND)).thenReturn(true);

        service.onServiceInvoiceRefunded(refund(invoiceId));

        verify(revenueFacts, never()).save(any());
    }

    // ------------------------------------------------------------------ doanh thu thuốc

    private MedicineInvoicePaidEvent medicinePaid(BigDecimal discount, BigDecimal payable) {
        // Clavamox 10 x 15.000 (VAT 5%) = 157.500; Vitamin 3 x 33.333,33... để kiểm tra phần dư làm tròn
        var a = new MedicineInvoicePaidEvent.Line(UUID.randomUUID(), "AMOX", "Clavamox", "ANTIBIOTIC", "Kháng sinh", 10, new BigDecimal("15000.00"), new BigDecimal("0.05"));
        var b = new MedicineInvoicePaidEvent.Line(UUID.randomUUID(), "VIT", "Vitamin", "SUPPLEMENT", "Thực phẩm chức năng", 3, new BigDecimal("33333.33"), new BigDecimal("0.05"));
        return new MedicineInvoicePaidEvent(UUID.randomUUID(), lateEvening, cashierId, UUID.randomUUID(), "MINV-1", UUID.randomUUID(), visitId, payable, discount,
                lateEvening, List.of(a, b));
    }

    @Test
    void medicinePaidAllocatesDiscountAndRowsAddUpToPayable() {
        when(visitFacts.findById(visitId)).thenReturn(Optional.of(fact("WAITING", false)));
        // gross = 157.500 + 104.999,9895 = 262.499,9895 ; giảm 10.000 => phải trả round(252.499,9895) = 252.500,00
        var event = medicinePaid(new BigDecimal("10000.00"), new BigDecimal("252500.00"));

        service.onMedicineInvoicePaid(event);

        ArgumentCaptor<RevenueFact> captor = ArgumentCaptor.forClass(RevenueFact.class);
        verify(revenueFacts, org.mockito.Mockito.times(2)).save(captor.capture());
        List<RevenueFact> rows = new ArrayList<>(captor.getAllValues());
        BigDecimal net = rows.stream().map(RevenueFact::getNetAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = rows.stream().map(RevenueFact::getDiscountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo("252500.00");
        assertThat(discount).isEqualByComparingTo("10000.00");
        for (RevenueFact row : rows) {
            assertThat(row.getRevenueType()).isEqualTo(RevenueType.MEDICINE);
            assertThat(row.getAmountBeforeVat().add(row.getVatAmount()).subtract(row.getDiscountAmount())).isEqualByComparingTo(row.getNetAmount());
            assertThat(row.getDoctorName()).isEqualTo("BS Tiến");
        }
        verify(summaries).accumulate(eq(LocalDate.of(2026, 11, 21)), eq("MEDICINE"), eq("ANTIBIOTIC"), eq("Kháng sinh"), eq(1), any(), any(), any(), any());
        verify(summaries).accumulate(eq(LocalDate.of(2026, 11, 21)), eq("MEDICINE"), eq("SUPPLEMENT"), eq("Thực phẩm chức năng"), eq(1), any(), any(), any(), any());
        // doanh thu thuốc không tính vào doanh thu dịch vụ của Bác sĩ
        verify(performances, never()).accumulate(any(), any(), anyString(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void medicinePaidOldFormatWithoutLinesWritesNothing() {
        var old = new MedicineInvoicePaidEvent(UUID.randomUUID(), lateEvening, cashierId, UUID.randomUUID(), "MINV-1", UUID.randomUUID(), visitId,
                new BigDecimal("157500.00"));

        service.onMedicineInvoicePaid(old);

        verify(revenueFacts, never()).save(any());
    }

    // ------------------------------------------------------------------ snapshot tên

    @Test
    void userProfileUpdateRenamesDoctorAndOwnerRows() {
        UUID userId = UUID.randomUUID();

        service.onUserProfileUpdated(new UserProfileUpdatedEvent(UUID.randomUUID(), lateEvening, userId, userId, "Tên Mới", "0900000000", "a@b.c", null));

        verify(visitFacts).renameDoctor(userId, "Tên Mới");
        verify(visitFacts).renameOwner(userId, "Tên Mới");
        verify(revenueFacts).renameDoctor(userId, "Tên Mới");
        verify(performances).renameDoctor(userId, "Tên Mới");
    }

    @Test
    void petUpdateRewritesPetAndOwnerOnVisitFacts() {
        service.onPetUpdated(new PetUpdatedEvent(UUID.randomUUID(), lateEvening, cashierId, petId, "Lu Lu", ownerId, "Lan B", "0900000000"));

        verify(visitFacts).updatePet(petId, "Lu Lu", ownerId, "Lan B");
    }
}
