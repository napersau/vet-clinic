package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.PetGender;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.request.CreatePetRequest;
import com.vetclinic.clinical.dto.request.UpdatePetRequest;
import com.vetclinic.clinical.dto.response.PetResponse;
import com.vetclinic.clinical.dto.response.PetSummaryResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.entity.Pet;
import com.vetclinic.clinical.mapper.ClinicalMapperImpl;
import com.vetclinic.clinical.repository.PetRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.SpeciesRepository;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.clinical.testsupport.TestData;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.event.DomainEvent;
import com.vetclinic.common.event.PetUpdatedEvent;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.vetclinic.clinical.testsupport.TestData.OWNER;
import static com.vetclinic.clinical.testsupport.TestData.RECEPTIONIST;
import static com.vetclinic.clinical.testsupport.TestData.assertError;
import static com.vetclinic.clinical.testsupport.TestData.dog;
import static com.vetclinic.clinical.testsupport.TestData.loginAs;
import static com.vetclinic.clinical.testsupport.TestData.pet;
import static com.vetclinic.clinical.testsupport.TestData.pid;
import static com.vetclinic.clinical.testsupport.TestData.uid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hồ sơ thú cưng v4.0: UUIDv7, snapshot tên / SĐT chủ nuôi, vi mạch + dị ứng, xóa mềm, {@code PetUpdatedEvent} qua outbox, tìm theo SĐT bằng
 * snapshot.
 */
@ExtendWith(MockitoExtension.class)
class PetServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T02:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final UUID NEW_OWNER = uid(13);

    @Mock
    private PetRepository petRepository;
    @Mock
    private SpeciesRepository speciesRepository;
    @Mock
    private PetVisitRepository visitRepository;
    @Mock
    private AuthClient authClient;
    @Mock
    private VisitAssembler assembler;
    @Mock
    private OutboxWriter outboxWriter;

    private PetServiceImpl service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new PetServiceImpl(petRepository, speciesRepository, visitRepository, authClient, new ClinicalMapperImpl(),
                assembler, outboxWriter, CLOCK, tm);
        lenient().when(petRepository.saveAndFlush(any(Pet.class))).thenAnswer(inv -> {
            Pet saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(pid(500));
            }
            return saved;
        });
        lenient().when(speciesRepository.findById((short) 1)).thenReturn(Optional.of(dog()));
        lenient().when(authClient.getUser(OWNER)).thenReturn(ApiResponse.success(
                new UserSummaryResponse(OWNER, "Hoàng Thị Lan", "0912000008", RoleCode.PET_OWNER, List.of(RoleCode.PET_OWNER), null, "ACTIVE")));
        lenient().when(authClient.getUser(NEW_OWNER)).thenReturn(ApiResponse.success(
                new UserSummaryResponse(NEW_OWNER, "Phạm Văn Nam", "0912000009", RoleCode.PET_OWNER, List.of(RoleCode.PET_OWNER), null, "ACTIVE")));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static CreatePetRequest create(UUID ownerId, String microchip) {
        return new CreatePetRequest(ownerId, (short) 1, "  Lu  ", "Mèo Anh", PetGender.FEMALE, null, null, true, microchip, null, " Dị ứng penicillin ", null);
    }

    // ================================================================== tạo hồ sơ

    @Test
    void create_byOwner_snapshotsTheOwnerNameAndPhoneFromAuthService() {
        loginAs(OWNER, RoleCode.PET_OWNER);

        PetResponse response = service.create(create(uid(99), "  900123456789012 "));   // ownerId trong body bị bỏ qua

        ArgumentCaptor<Pet> saved = ArgumentCaptor.forClass(Pet.class);
        verify(petRepository).saveAndFlush(saved.capture());
        Pet pet = saved.getValue();
        assertThat(pet.getOwnerId()).isEqualTo(OWNER);
        assertThat(pet.getOwnerNameSnapshot()).isEqualTo("Hoàng Thị Lan");
        assertThat(pet.getOwnerPhoneSnapshot()).isEqualTo("0912000008");
        assertThat(pet.getName()).isEqualTo("Lu");
        assertThat(pet.getMicrochipNumber()).isEqualTo("900123456789012");
        assertThat(pet.getAllergies()).isEqualTo("Dị ứng penicillin");
        assertThat(pet.getPetCode()).matches("PET-2026-[A-Z2-9]{6}");   // khóa chính là UUID nên mã hiển thị có đuôi ngẫu nhiên
        assertThat(response.id()).isEqualTo(pid(500));
        assertThat(response.ownerName()).isEqualTo("Hoàng Thị Lan");
        assertThat(response.ownerPhone()).isEqualTo("0912000008");
    }

    @Test
    void create_byReceptionist_needsAnActivePetOwner() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.create(create(null, null)));
        when(authClient.getUser(uid(3))).thenReturn(ApiResponse.success(
                new UserSummaryResponse(uid(3), "Bác sĩ", "0911111111", RoleCode.VETERINARIAN, List.of(RoleCode.VETERINARIAN), "P01", "ACTIVE")));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.create(create(uid(3), null)));
        when(authClient.getUser(uid(4))).thenReturn(ApiResponse.success(
                new UserSummaryResponse(uid(4), "Chủ cũ", "0922222222", RoleCode.PET_OWNER, List.of(RoleCode.PET_OWNER), null, "LOCKED")));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.create(create(uid(4), null)));

        service.create(create(NEW_OWNER, null));
        ArgumentCaptor<Pet> saved = ArgumentCaptor.forClass(Pet.class);
        verify(petRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOwnerNameSnapshot()).isEqualTo("Phạm Văn Nam");
    }

    @Test
    void create_refusesAMicrochipThatBelongsToAnotherPet() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        when(petRepository.existsByMicrochipNumber("900123456789012")).thenReturn(true);

        assertError(ErrorCode.CODE_ALREADY_EXISTS, () -> service.create(create(null, "900123456789012")));
        verify(petRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_retriesUntilThePetCodeIsUnique() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        when(petRepository.existsByPetCode(any())).thenReturn(true, true, false);

        service.create(create(null, null));

        verify(petRepository, org.mockito.Mockito.times(3)).existsByPetCode(any());
    }

    // ================================================================== sửa hồ sơ + PetUpdatedEvent

    private Pet storedPet() {
        return pet(pid(11), dog());
    }

    @Test
    void update_renamingThePetWritesAPetUpdatedEventInTheSameTransaction() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        Pet stored = storedPet();
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(stored));

        PetResponse response = service.update(pid(11), new UpdatePetRequest(" Lu Lu ", null, null, null, null, null, null, null, null, null, null));

        assertThat(response.name()).isEqualTo("Lu Lu");
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).write(eq("PET"), eq(pid(11)), event.capture());
        PetUpdatedEvent updated = (PetUpdatedEvent) event.getValue();
        assertThat(updated.routingKey()).isEqualTo("pet.updated");
        assertThat(updated.petId()).isEqualTo(pid(11));
        assertThat(updated.petName()).isEqualTo("Lu Lu");
        assertThat(updated.ownerId()).isEqualTo(OWNER);
        assertThat(updated.actorUserId()).isEqualTo(OWNER);
    }

    @Test
    void update_changingOnlyNonSnapshotFields_writesNoEvent() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        Pet stored = storedPet();
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(stored));

        PetResponse response = service.update(pid(11), new UpdatePetRequest(null, null, "Poodle", null, null, null, null, "900111", null, "Dị ứng sulfa", null));

        assertThat(response.breed()).isEqualTo("Poodle");
        assertThat(response.microchipNumber()).isEqualTo("900111");
        assertThat(response.allergies()).isEqualTo("Dị ứng sulfa");
        verify(outboxWriter, never()).write(any(), any(), any(DomainEvent.class));
    }

    @Test
    void update_anEmptyMicrochipOrAllergyClearsIt_andAMicrochipOfAnotherPetIsRefused() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        Pet stored = storedPet();
        stored.setMicrochipNumber("900111");
        stored.setAllergies("x");
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(stored));

        service.update(pid(11), new UpdatePetRequest(null, null, null, null, null, null, null, "", null, "", null));
        assertThat(stored.getMicrochipNumber()).isNull();
        assertThat(stored.getAllergies()).isNull();

        when(petRepository.existsByMicrochipNumberAndIdNot("900222", pid(11))).thenReturn(true);
        assertError(ErrorCode.CODE_ALREADY_EXISTS,
                () -> service.update(pid(11), new UpdatePetRequest(null, null, null, null, null, null, null, "900222", null, null, null)));
    }

    @Test
    void update_onlyAReceptionistMayTransferThePetToAnotherOwner_andTheEventCarriesTheNewOwner() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        assertError(ErrorCode.ACCESS_DENIED,
                () -> service.update(pid(11), new UpdatePetRequest(null, NEW_OWNER, null, null, null, null, null, null, null, null, null)));

        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Pet stored = storedPet();
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(stored));

        PetResponse response = service.update(pid(11), new UpdatePetRequest(null, NEW_OWNER, null, null, null, null, null, null, null, null, null));

        assertThat(response.ownerId()).isEqualTo(NEW_OWNER);
        assertThat(response.ownerName()).isEqualTo("Phạm Văn Nam");   // snapshot chủ mới
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(outboxWriter).write(eq("PET"), eq(pid(11)), event.capture());
        PetUpdatedEvent updated = (PetUpdatedEvent) event.getValue();
        assertThat(updated.ownerId()).isEqualTo(NEW_OWNER);
        assertThat(updated.ownerName()).isEqualTo("Phạm Văn Nam");
        assertThat(updated.ownerPhone()).isEqualTo("0912000009");
    }

    @Test
    void update_ofSomeoneElsesPetLooksLikeNotFound() {
        loginAs(uid(77), RoleCode.PET_OWNER);
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(storedPet()));

        assertError(ErrorCode.PET_NOT_FOUND,
                () -> service.update(pid(11), new UpdatePetRequest("Trộm", null, null, null, null, null, null, null, null, null, null)));
        verify(outboxWriter, never()).write(any(), any(), any(DomainEvent.class));
    }

    // ================================================================== xóa mềm

    @Test
    void delete_isASoftDeleteThatKeepsTheRow() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        Pet stored = storedPet();
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(stored));

        service.delete(pid(11));

        assertThat(stored.getDeletedAt()).isEqualTo(CLOCK.instant());
        verify(petRepository).save(stored);
        verify(petRepository, never()).delete(any(Pet.class));
        verify(petRepository, never()).deleteById(any());
    }

    @Test
    void delete_isRefusedWhileThePetHasAnOpenVisit() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        Pet stored = storedPet();
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(stored));
        when(visitRepository.existsByPetIdAndStatusIn(pid(11), VisitStatus.ACTIVE)).thenReturn(true);

        assertError(ErrorCode.PET_HAS_OPEN_VISIT, () -> service.delete(pid(11)));
        assertThat(stored.getDeletedAt()).isNull();
    }

    @Test
    void delete_ofAnAlreadyDeletedOrForeignPetIsNotFound() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(12))).thenReturn(Optional.empty());
        assertError(ErrorCode.PET_NOT_FOUND, () -> service.delete(pid(12)));

        loginAs(uid(77), RoleCode.PET_OWNER);
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(storedPet()));
        assertError(ErrorCode.PET_NOT_FOUND, () -> service.delete(pid(11)));
    }

    @Test
    void delete_isForbiddenForOtherRoles() {
        loginAs(uid(3), RoleCode.VETERINARIAN);

        assertError(ErrorCode.ACCESS_DENIED, () -> service.delete(pid(11)));
    }

    // ================================================================== tra cứu

    @Test
    void getSummary_returnsTheOwnerAndTheSnapshotsForTheAppointmentService() {
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(storedPet()));

        PetSummaryResponse summary = service.getSummary(pid(11));

        assertThat(summary.id()).isEqualTo(pid(11));
        assertThat(summary.ownerId()).isEqualTo(OWNER);
        assertThat(summary.name()).isEqualTo("Lu");
        assertThat(summary.ownerName()).isEqualTo("Hoàng Thị Lan");
        assertThat(summary.ownerPhone()).isEqualTo("0912000008");
    }

    @Test
    void getSummary_ofADeletedOrMissingPetIsNotFound() {
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(404))).thenReturn(Optional.empty());

        assertError(ErrorCode.PET_NOT_FOUND, () -> service.getSummary(pid(404)));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void search_byOwnerPhone_usesTheSnapshotColumnAndHidesDeletedPets() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        var captor = ArgumentCaptor.forClass(Specification.class);
        when(petRepository.findAll(captor.capture(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.search(null, " 0912000008 ", null, 0, 20);

        Specification<Pet> applied = captor.getValue();
        jakarta.persistence.criteria.Root<Pet> root = mock(jakarta.persistence.criteria.Root.class);
        jakarta.persistence.criteria.CriteriaBuilder cb = mock(jakarta.persistence.criteria.CriteriaBuilder.class);
        jakarta.persistence.criteria.Path phonePath = mock(jakarta.persistence.criteria.Path.class);
        jakarta.persistence.criteria.Path deletedPath = mock(jakarta.persistence.criteria.Path.class);
        when(root.get("deletedAt")).thenReturn(deletedPath);
        when(root.get("ownerPhoneSnapshot")).thenReturn(phonePath);
        when(cb.isNull(deletedPath)).thenReturn(mock(jakarta.persistence.criteria.Predicate.class));
        when(cb.equal(any(), any(Object.class))).thenReturn(mock(jakarta.persistence.criteria.Predicate.class));
        when(cb.and(any(jakarta.persistence.criteria.Predicate[].class))).thenReturn(mock(jakarta.persistence.criteria.Predicate.class));
        applied.toPredicate(root, mock(jakarta.persistence.criteria.CriteriaQuery.class), cb);

        verify(cb).isNull(deletedPath);                    // hồ sơ đã xóa mềm bị ẩn
        verify(cb).equal(phonePath, "0912000008");         // tìm theo SĐT trên snapshot, không gọi auth-service
        verify(authClient, never()).getUser(any());
    }

    @Test
    void search_byOwner_ignoresTheOwnerFilterAndPhoneParameters() {
        loginAs(OWNER, RoleCode.PET_OWNER);
        when(petRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        // Chủ nuôi gửi ownerId của người khác và SĐT: bị bỏ qua (chỉ thấy thú cưng của mình); không lỗi
        assertThat(service.search(uid(99), "0900000000", null, 0, 20).getItems()).isEmpty();
    }

    @Test
    void getById_attachesTheWeightOfTheMostRecentWeighedVisit_becausePetsHasNoWeightColumn() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(storedPet()));
        when(visitRepository.findLatestWeights(List.of(pid(11))))
                .thenReturn(Collections.singletonList(new Object[]{pid(11), new BigDecimal("4.60")}));

        PetResponse response = service.getById(pid(11));

        assertThat(response.weight()).isEqualByComparingTo("4.60");   // = weight_at_visit của đợt khám gần nhất
        assertThat(response.microchipNumber()).isNull();
    }

    @Test
    void getById_weightIsNullWhenThePetWasNeverWeighed() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        when(petRepository.findWithSpeciesByIdAndDeletedAtIsNull(pid(11))).thenReturn(Optional.of(storedPet()));
        when(visitRepository.findLatestWeights(List.of(pid(11)))).thenReturn(List.of());

        assertThat(service.getById(pid(11)).weight()).isNull();
    }

    // ================================================================== snapshot theo sự kiện

    @Test
    void refreshOwnerSnapshot_updatesEveryPetOfTheOwnerWithOneStatement() {
        when(petRepository.updateOwnerSnapshot(OWNER, "Hoàng Lan", "0912999999")).thenReturn(2);

        assertThat(service.refreshOwnerSnapshot(OWNER, "Hoàng Lan", "0912999999")).isEqualTo(2);
        assertThat(service.refreshOwnerSnapshot(OWNER, null, "0912999999")).isZero();   // thiếu dữ liệu: bỏ qua
        assertThat(service.refreshOwnerSnapshot(null, "A", "B")).isZero();
        verify(petRepository, org.mockito.Mockito.times(1)).updateOwnerSnapshot(any(), any(), any());
    }
}
