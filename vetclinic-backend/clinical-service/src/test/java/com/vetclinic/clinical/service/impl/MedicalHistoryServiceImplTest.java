package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.response.MedicalHistoryEntry;
import com.vetclinic.clinical.dto.response.MedicalRecordResponse;
import com.vetclinic.clinical.dto.response.PrescriptionSummary;
import com.vetclinic.clinical.service.PrescriptionService;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.repository.PetRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.dto.PageResponse;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

import static com.vetclinic.clinical.testsupport.TestData.uid;
import static com.vetclinic.clinical.testsupport.TestData.DOCTOR;
import static com.vetclinic.clinical.testsupport.TestData.OWNER;
import static com.vetclinic.clinical.testsupport.TestData.RECEPTIONIST;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.dog;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.pet;
import static com.vetclinic.clinical.testsupport.TestData.visit;
import static com.vetclinic.clinical.testsupport.TestData.lid;
import static com.vetclinic.clinical.testsupport.TestData.pid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MedicalHistoryServiceImplTest {

    @Mock
    private PetRepository petRepository;
    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private VisitAssembler assembler;
    @Mock
    private AuthClient authClient;
    @Mock
    private PrescriptionService prescriptionService;

    private MedicalHistoryServiceImpl service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new MedicalHistoryServiceImpl(petRepository, visitRepository, assembler, authClient, prescriptionService, tm);
        lenient().when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(pet(pid(11), dog())));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static VisitResponse response(UUID id) {
        MedicalRecordResponse record = new MedicalRecordResponse(id, null, null, null, "Viêm da", null, null, null, null, null, 1L, true);
        return new VisitResponse(id, "VIS-2026-" + id.toString().substring(31), pid(11), "Lu", "PET-2026-11", "Chó", OWNER, null, DOCTOR, null, null,
                RECEPTIONIST, "P01", false, VisitStatus.COMPLETED, true, null, null, null,
                null, null, null, null, List.of(), List.of(), List.of(), List.of(), record);
    }

    private void stubVisits(long... ids) {
        List<PetVisit> visits = Arrays.stream(ids).mapToObj(i -> visit(i, VisitStatus.COMPLETED, DOCTOR)).toList();
        lenient().when(visitRepository.findByPetId(eq(pid(11)), any(Pageable.class))).thenReturn(new PageImpl<>(visits));
        lenient().when(visitRepository.findByPetIdAndStatus(eq(pid(11)), eq(VisitStatus.COMPLETED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(visits));
        lenient().when(assembler.detail(any(PetVisit.class))).thenAnswer(inv -> response(((PetVisit) inv.getArgument(0)).getId()));
    }

    private static PrescriptionSummary prescription(long visitId) {
        return new PrescriptionSummary(uid(3), lid(visitId), null, List.of());
    }

    @Test
    void staff_seeTheTimelineWithDoctorNameAndPrescription() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        stubVisits(7L, 8L);
        when(prescriptionService.summariesByVisit(List.of(lid(7L), lid(8L)))).thenReturn(Map.of(lid(8L), prescription(8L)));
        when(authClient.getUsers(any())).thenReturn(ApiResponse.success(List.of(
                new UserSummaryResponse(DOCTOR, "BS Lan", "0911111111", RoleCode.VETERINARIAN, List.of(RoleCode.VETERINARIAN), "P01", "ACTIVE"))));

        PageResponse<MedicalHistoryEntry> page = service.history(pid(11), 0, 10);

        assertThat(page.getItems()).hasSize(2);
        assertThat(page.getItems()).extracting(MedicalHistoryEntry::doctorName).containsOnly("BS Lan");
        assertThat(page.getItems().get(0).prescription()).isNull();              // ca 7 không kê đơn
        assertThat(page.getItems().get(1).prescription().id()).isEqualTo(uid(3));   // đơn thuốc không có trạng thái
        verify(visitRepository).findByPetId(eq(pid(11)), any(Pageable.class));
    }

    @Test
    void owner_seesOnlyCompletedVisitsOfOwnPet() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        stubVisits(7L);
        when(prescriptionService.summariesByVisit(any())).thenReturn(Map.of());
        when(authClient.getUsers(any())).thenReturn(ApiResponse.success(List.of()));

        PageResponse<MedicalHistoryEntry> page = service.history(pid(11), 0, 10);

        assertThat(page.getItems()).hasSize(1);
        verify(visitRepository).findByPetIdAndStatus(eq(pid(11)), eq(VisitStatus.COMPLETED), any(Pageable.class));
        verify(visitRepository, never()).findByPetId(any(), any(Pageable.class));
    }

    @Test
    void owner_ofAnotherPetGetsNotFound() {
        loginAs(uid(6), RoleCode.PET_OWNER);

        assertError(ErrorCode.PET_NOT_FOUND, () -> service.history(pid(11), 0, 10));
        verify(visitRepository, never()).findByPetIdAndStatus(any(), any(), any(Pageable.class));
    }

    @Test
    void unknownPet() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);

        assertError(ErrorCode.PET_NOT_FOUND, () -> service.history(pid(404), 0, 10));
    }

    @Test
    void prescriptionOrAuthDown_stillReturnsTheMedicalRecord() {
        loginAs(DOCTOR, RoleCode.VETERINARIAN);
        stubVisits(7L);
        when(prescriptionService.summariesByVisit(any())).thenThrow(new RuntimeException("db down"));
        when(authClient.getUsers(any())).thenThrow(new RuntimeException("auth down"));

        PageResponse<MedicalHistoryEntry> page = service.history(pid(11), 0, 10);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).prescription()).isNull();
        assertThat(page.getItems().get(0).doctorName()).isNull();
        assertThat(page.getItems().get(0).visit().medicalRecord().finalDiagnosis()).isEqualTo("Viêm da");
    }

    @Test
    void emptyHistory_makesNoExternalCalls() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(visitRepository.findByPetId(eq(pid(11)), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        assertThat(service.history(pid(11), 0, 10).getItems()).isEmpty();
        verify(prescriptionService, never()).summariesByVisit(any());
        verify(authClient, never()).getUsers(any());
    }
}
