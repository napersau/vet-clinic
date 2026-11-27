package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.MedicineClient;
import com.vetclinic.clinical.client.MedicineInvoiceClient;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.request.PrescriptionItemRequest;
import com.vetclinic.clinical.dto.request.PrescriptionRequest;
import com.vetclinic.clinical.dto.response.MedicineInfo;
import com.vetclinic.clinical.dto.response.MedicineInvoiceStatus;
import com.vetclinic.clinical.dto.response.PrescriptionResponse;
import com.vetclinic.clinical.dto.response.PrescriptionSummary;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.Prescription;
import com.vetclinic.clinical.entity.PrescriptionItem;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.PrescriptionRepository;
import com.vetclinic.clinical.service.VisitSupport;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.OTHER_DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.OWNER;
import static com.vetclinic.clinical.testsupport.TestData.RECEPTIONIST;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.lid;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.uid;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrescriptionServiceImplTest {

    private static final UUID VISIT = lid(100L);
    private static final UUID AMOX = uid(301);
    private static final UUID DRON = uid(302);
    private static final UUID PRESCRIPTION = uid(700);

    @Mock
    private PrescriptionRepository prescriptionRepository;
    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private VisitSupport support;
    @Mock
    private MedicineClient medicineClient;
    @Mock
    private MedicineInvoiceClient invoiceClient;

    private PrescriptionServiceImpl service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(prescriptionRepository.saveAndFlush(any(Prescription.class))).thenAnswer(inv -> {
            Prescription p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(PRESCRIPTION);
            }
            return p;
        });
        lenient().when(medicineClient.getMedicine(AMOX)).thenReturn(ApiResponse.success(
                new MedicineInfo(AMOX, "MED_AMOX", "Clavamox 250mg", "Viên", 200, 20, true, List.of(new MedicineInfo.Ingredient("Amoxicillin")))));
        lenient().when(medicineClient.getMedicine(DRON)).thenReturn(ApiResponse.success(
                new MedicineInfo(DRON, "MED_DRON", "Drontal Plus", "Viên", 1, 10, true, List.of(new MedicineInfo.Ingredient("Praziquantel")))));
        service = new PrescriptionServiceImpl(prescriptionRepository, visitRepository, support, medicineClient, invoiceClient, tm);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private PetVisit inProgressVisit() {
        PetVisit visit = visit(100L, VisitStatus.IN_PROGRESS, DOCTOR);
        visit.setDoctorNameSnapshot("BS Tiến");
        lenient().when(visitRepository.findById(VISIT)).thenReturn(Optional.of(visit));
        lenient().when(support.lock(VISIT)).thenReturn(visit);
        return visit;
    }

    private static PrescriptionItemRequest rx(UUID medicineId, int quantity) {
        return new PrescriptionItemRequest(medicineId, quantity, "1 viên", "2 lần/ngày", 5, null);
    }

    private static PrescriptionRequest request(PrescriptionItemRequest... items) {
        return new PrescriptionRequest("Cho ăn sau", List.of(items));
    }

    private static Prescription existing() {
        Prescription p = Prescription.builder().id(PRESCRIPTION).visitId(VISIT).build();
        p.addItem(PrescriptionItem.builder().id(1L).medicineId(AMOX).medicineNameSnapshot("Clavamox 250mg").unitSnapshot("Viên").quantity(10)
                .dosage("1 viên").frequency("2 lần/ngày").durationDays(5).build());
        p.addItem(PrescriptionItem.builder().id(2L).medicineId(DRON).medicineNameSnapshot("Drontal Plus").unitSnapshot("Viên").quantity(2)
                .dosage("1 viên").frequency("1 lần").durationDays(1).build());
        return p;
    }

    // ================================================================== kê đơn

    @Test
    void save_createsPrescription_snapshotsNameAndUnit_noPriceNoStatus() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        inProgressVisit();
        when(prescriptionRepository.findWithItemsByVisitId(VISIT)).thenReturn(Optional.empty());

        PrescriptionResponse response = service.save(VISIT, request(rx(AMOX, 10)));

        assertThat(response.id()).isEqualTo(PRESCRIPTION);
        assertThat(response.visitId()).isEqualTo(VISIT);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).medicineName()).isEqualTo("Clavamox 250mg");   // bản chụp lúc kê
        assertThat(response.items().get(0).unit()).isEqualTo("Viên");
        assertThat(response.items().get(0).quantity()).isEqualTo(10);
        assertThat(response.doctorName()).isEqualTo("BS Tiến");
        assertThat(response.stockWarnings()).isEmpty();
        assertThat(response.allergyWarning()).isNull();
        verify(invoiceClient, never()).status(any());   // đơn mới chưa có hóa đơn để kiểm tra
    }

    @Test
    void save_overStockOnlyWarns_EX13() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        inProgressVisit();
        when(prescriptionRepository.findWithItemsByVisitId(VISIT)).thenReturn(Optional.empty());

        PrescriptionResponse response = service.save(VISIT, request(rx(AMOX, 10), rx(DRON, 5)));   // Drontal chỉ còn 1

        assertThat(response.items()).hasSize(2);   // vẫn lưu
        assertThat(response.stockWarnings()).hasSize(1).first().asString().contains("Drontal Plus").contains("tồn kho 1");
    }

    @Test
    void save_warnsWhenAMedicineMatchesThePetsAllergyHistory_ignoringCaseAndDiacritics() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit visit = inProgressVisit();
        visit.getPet().setAllergies("Sữa bò, AMOXICILLIN");
        when(prescriptionRepository.findWithItemsByVisitId(VISIT)).thenReturn(Optional.empty());

        PrescriptionResponse response = service.save(VISIT, request(rx(AMOX, 10), rx(DRON, 1)));

        assertThat(response.allergyWarning()).contains("Clavamox 250mg").doesNotContain("Drontal");
        assertThat(response.items()).hasSize(2);   // chỉ cảnh báo, không chặn
    }

    @Test
    void save_updatesInPlaceByMedicine_removesDroppedLinesAndAddsNewOnes() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        inProgressVisit();
        Prescription existing = existing();
        PrescriptionItem amoxLine = existing.getItems().get(0);
        when(prescriptionRepository.findWithItemsByVisitId(VISIT)).thenReturn(Optional.of(existing));
        when(invoiceClient.status(PRESCRIPTION)).thenReturn(ApiResponse.success(new MedicineInvoiceStatus(PRESCRIPTION, uid(900), "PENDING")));
        UUID third = uid(303);
        when(medicineClient.getMedicine(third)).thenReturn(ApiResponse.success(new MedicineInfo(third, "MED_V", "Vitamin", "Viên", 50, 5, true, List.of())));

        service.save(VISIT, request(rx(AMOX, 12), rx(third, 3)));   // bỏ Drontal, sửa Clavamox, thêm Vitamin

        assertThat(existing.getItems()).extracting(PrescriptionItem::getMedicineId).containsExactlyInAnyOrder(AMOX, third);
        assertThat(existing.getItems()).contains(amoxLine);   // dòng cũ được sửa tại chỗ (không xóa rồi thêm: UNIQUE(prescription, medicine))
        assertThat(amoxLine.getQuantity()).isEqualTo(12);
        assertThat(existing.getDoctorNotes()).isEqualTo("Cho ăn sau");
    }

    @Test
    void save_isRefusedOnceTheMedicineInvoiceIsPaid_PHARM_1411() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        inProgressVisit();   // đã mở lại hồ sơ (T14) nhưng khách đã trả tiền thuốc
        when(prescriptionRepository.findWithItemsByVisitId(VISIT)).thenReturn(Optional.of(existing()));
        when(invoiceClient.status(PRESCRIPTION)).thenReturn(ApiResponse.success(new MedicineInvoiceStatus(PRESCRIPTION, uid(900), "PAID")));

        assertError(ErrorCode.PRESCRIPTION_LOCKED, () -> service.save(VISIT, request(rx(AMOX, 1))));
        verify(prescriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void save_isAllowedWhenTheInvoiceIsCancelledOrStillDraft() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        inProgressVisit();
        when(prescriptionRepository.findWithItemsByVisitId(VISIT)).thenReturn(Optional.of(existing()));
        when(invoiceClient.status(PRESCRIPTION)).thenReturn(ApiResponse.success(new MedicineInvoiceStatus(PRESCRIPTION, uid(900), "CANCELLED")));

        assertThat(service.save(VISIT, request(rx(AMOX, 1))).items()).hasSize(1);
    }

    @Test
    void save_onlyTheAssignedDoctorWhileInProgress() {
        loginAs(OTHER_DOCTOR, RoleCode.VETERINARIAN);
        inProgressVisit();
        assertError(ErrorCode.NOT_ASSIGNED_DOCTOR, () -> service.save(VISIT, request(rx(AMOX, 1))));

        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        PetVisit closed = visit(100L, VisitStatus.WAITING_PAYMENT, DOCTOR);
        when(visitRepository.findById(VISIT)).thenReturn(Optional.of(closed));
        assertError(ErrorCode.PRESCRIPTION_LOCKED, () -> service.save(VISIT, request(rx(AMOX, 1))));   // ca đã chốt: khóa
        verify(medicineClient, never()).getMedicine(any());   // từ chối trước khi gọi billing
    }

    @Test
    void save_unknownVisit() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        when(visitRepository.findById(VISIT)).thenReturn(Optional.empty());

        assertError(ErrorCode.VISIT_NOT_FOUND, () -> service.save(VISIT, request(rx(AMOX, 1))));
    }

    @Test
    void save_inactiveOrUnknownMedicineAndDuplicateLinesAreRefused() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        inProgressVisit();
        when(prescriptionRepository.findWithItemsByVisitId(VISIT)).thenReturn(Optional.empty());
        when(medicineClient.getMedicine(DRON)).thenReturn(ApiResponse.success(
                new MedicineInfo(DRON, "MED_DRON", "Drontal Plus", "Viên", 1, 10, false, List.of())));   // ngừng kinh doanh

        assertError(ErrorCode.MEDICINE_NOT_FOUND, () -> service.save(VISIT, request(rx(DRON, 1))));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.save(VISIT, request(rx(AMOX, 1), rx(AMOX, 2))));
        verify(prescriptionRepository, never()).saveAndFlush(any());
    }

    @Test
    void save_concurrentFirstPrescriptionCaughtByUniqueVisitId() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        inProgressVisit();
        when(prescriptionRepository.findWithItemsByVisitId(VISIT)).thenReturn(Optional.empty());
        when(prescriptionRepository.saveAndFlush(any(Prescription.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertError(ErrorCode.PRESCRIPTION_ALREADY_EXISTS, () -> service.save(VISIT, request(rx(AMOX, 1))));
    }

    // ================================================================== tra cứu

    @Test
    void get_staffSeesTheDraftMedicineInvoiceOfThePrescription() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        inProgressVisit();
        when(prescriptionRepository.findWithItemsByVisitId(VISIT)).thenReturn(Optional.of(existing()));
        when(invoiceClient.status(PRESCRIPTION)).thenReturn(ApiResponse.success(new MedicineInvoiceStatus(PRESCRIPTION, uid(900), "PENDING")));

        PrescriptionResponse response = service.getByVisit(VISIT);

        assertThat(response.items()).hasSize(2);
        assertThat(response.medicineInvoiceId()).isEqualTo(uid(900));
        assertThat(response.medicineInvoiceStatus()).isEqualTo("PENDING");
        assertThat(response.ownerName()).isEqualTo("Hoàng Thị Lan");
    }

    @Test
    void get_billingDownStillReturnsThePrescription() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        inProgressVisit();
        when(prescriptionRepository.findWithItemsByVisitId(VISIT)).thenReturn(Optional.of(existing()));
        when(invoiceClient.status(any())).thenThrow(new RuntimeException("billing down"));

        PrescriptionResponse response = service.getByVisit(VISIT);

        assertThat(response.items()).hasSize(2);
        assertThat(response.medicineInvoiceId()).isNull();
    }

    @Test
    void get_noPrescriptionYet_PHARM_1404_andOwnerOfAnotherPetGetsNotFound() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        inProgressVisit();
        when(prescriptionRepository.findWithItemsByVisitId(VISIT)).thenReturn(Optional.empty());
        assertError(ErrorCode.PRESCRIPTION_NOT_FOUND, () -> service.getByVisit(VISIT));

        loginAs(uid(6), RoleCode.PET_OWNER);   // không phải chủ của Lu
        assertError(ErrorCode.PRESCRIPTION_NOT_FOUND, () -> service.getByVisit(VISIT));
    }

    @Test
    void get_ownerSeesTheirOwnPetsPrescription() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        inProgressVisit();
        when(prescriptionRepository.findWithItemsByVisitId(VISIT)).thenReturn(Optional.of(existing()));
        when(invoiceClient.status(any())).thenReturn(ApiResponse.success(new MedicineInvoiceStatus(PRESCRIPTION, null, null)));

        assertThat(service.print(VISIT).items()).hasSize(2);
    }

    @Test
    void summariesByVisit_mapsSnapshotsKeyedByVisit() {
        Prescription existing = existing();
        existing.setDoctorNotes("Uống đủ liều");
        when(prescriptionRepository.findWithItemsByVisitIdIn(List.of(VISIT, lid(101L)))).thenReturn(List.of(existing));

        Map<UUID, PrescriptionSummary> summaries = service.summariesByVisit(List.of(VISIT, lid(101L)));

        assertThat(summaries).containsOnlyKeys(VISIT);
        assertThat(summaries.get(VISIT).items()).extracting(PrescriptionSummary.Item::medicineName).containsExactly("Clavamox 250mg", "Drontal Plus");
        assertThat(service.summariesByVisit(List.of())).isEmpty();
    }
}
