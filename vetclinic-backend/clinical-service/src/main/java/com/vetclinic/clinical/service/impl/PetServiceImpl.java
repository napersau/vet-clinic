package com.vetclinic.clinical.service.impl;

import com.vetclinic.clinical.client.AuthClient;
import com.vetclinic.clinical.constant.PetGender;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.dto.request.CreatePetRequest;
import com.vetclinic.clinical.dto.request.UpdatePetRequest;
import com.vetclinic.clinical.dto.response.PetResponse;
import com.vetclinic.clinical.dto.response.PetSummaryResponse;
import com.vetclinic.clinical.dto.response.UserSummaryResponse;
import com.vetclinic.clinical.dto.response.VisitResponse;
import com.vetclinic.clinical.entity.Pet;
import com.vetclinic.clinical.entity.Species;
import com.vetclinic.clinical.mapper.ClinicalMapper;
import com.vetclinic.clinical.repository.PetRepository;
import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.repository.SpeciesRepository;
import com.vetclinic.clinical.service.PetService;
import com.vetclinic.clinical.service.VisitAssembler;
import com.vetclinic.common.dto.ApiResponse;
import com.vetclinic.common.dto.PageResponse;
import com.vetclinic.common.event.PetUpdatedEvent;
import com.vetclinic.common.event.outbox.OutboxWriter;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityUtils;
import com.vetclinic.common.util.Codes;
import com.vetclinic.common.util.UuidV7;
import jakarta.persistence.criteria.Predicate;
import lombok.extern.slf4j.Slf4j;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class PetServiceImpl implements PetService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int CODE_ATTEMPTS = 8;
    private static final String AGGREGATE = "PET";

    private final PetRepository petRepository;
    private final SpeciesRepository speciesRepository;
    private final PetVisitRepository visitRepository;
    private final AuthClient authClient;
    private final ClinicalMapper mapper;
    private final VisitAssembler assembler;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PetServiceImpl(PetRepository petRepository, SpeciesRepository speciesRepository,
                          PetVisitRepository visitRepository, AuthClient authClient, ClinicalMapper mapper,
                          VisitAssembler assembler, OutboxWriter outboxWriter, Clock clock,
                          PlatformTransactionManager transactionManager) {
        this.petRepository = petRepository;
        this.speciesRepository = speciesRepository;
        this.visitRepository = visitRepository;
        this.authClient = authClient;
        this.mapper = mapper;
        this.assembler = assembler;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private record Actor(UUID id, RoleCode role) {
    }

    /** Chủ nuôi của hồ sơ: id + tên + SĐT chụp snapshot. */
    private record Owner(UUID id, String name, String phone) {
    }

    /** Không @Transactional: lời gọi OpenFeign phải nằm ngoài transaction (quy chuẩn §9.1). */
    @Override
    public PetResponse create(CreatePetRequest request) {
        Actor actor = currentActor();
        Owner owner = switch (actor.role()) {
            // Chủ nuôi: ownerId lấy từ token, không bao giờ tin vào body
            case PET_OWNER -> fetchOwner(actor.id());
            case RECEPTIONIST -> {
                if (request.ownerId() == null) {
                    throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Lễ tân phải chọn chủ thú cưng (ownerId)");
                }
                yield fetchOwner(request.ownerId());
            }
            default -> throw new AppException(ErrorCode.ACCESS_DENIED);
        };

        return transactionTemplate.execute(tx -> {
            Species species = speciesRepository.findById(request.speciesId())
                    .orElseThrow(() -> new AppException(ErrorCode.SPECIES_NOT_FOUND));
            String microchip = blankToNull(request.microchipNumber());
            if (microchip != null && petRepository.existsByMicrochipNumber(microchip)) {
                throw new AppException(ErrorCode.CODE_ALREADY_EXISTS, "Số vi mạch đã được dùng cho thú cưng khác");
            }
            Pet pet = Pet.builder()
                    .petCode(newPetCode())
                    .ownerId(owner.id())
                    .ownerNameSnapshot(owner.name())
                    .ownerPhoneSnapshot(owner.phone())
                    .species(species)
                    .name(request.name().trim())
                    .breed(blankToNull(request.breed()))
                    .gender(request.gender() == null ? PetGender.UNKNOWN : request.gender())
                    .dateOfBirth(request.dateOfBirth())
                    .color(blankToNull(request.color()))
                    .neutered(Boolean.TRUE.equals(request.neutered()))
                    .microchipNumber(microchip)
                    .identifyingCharacteristics(blankToNull(request.identifyingCharacteristics()))
                    .allergies(blankToNull(request.allergies()))
                    .notes(blankToNull(request.notes()))
                    .build();
            return mapper.toResponse(petRepository.saveAndFlush(pet));
        });
    }

    /** Không @Transactional: chuyển chủ cần lời gọi Feign sang auth-service (ngoài transaction). */
    @Override
    public PetResponse update(UUID id, UpdatePetRequest request) {
        Actor actor = currentActor();
        if (actor.role() != RoleCode.PET_OWNER && actor.role() != RoleCode.RECEPTIONIST) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        Owner newOwner = null;
        if (request.ownerId() != null) {
            if (actor.role() != RoleCode.RECEPTIONIST) {
                throw new AppException(ErrorCode.ACCESS_DENIED, "Chỉ Lễ tân được chuyển hồ sơ thú cưng sang chủ khác");
            }
            newOwner = fetchOwner(request.ownerId());
        }
        Owner transferTo = newOwner;
        return transactionTemplate.execute(tx -> applyUpdate(actor, id, request, transferTo));
    }

    private PetResponse applyUpdate(Actor actor, UUID id, UpdatePetRequest request, Owner newOwner) {
        Pet pet = findPet(id);
        assertOwnerAccess(actor, pet);
        String oldName = pet.getName();
        UUID oldOwner = pet.getOwnerId();

        if (request.name() != null) {
            pet.setName(request.name().trim());
        }
        if (newOwner != null) {
            pet.setOwnerId(newOwner.id());
            pet.setOwnerNameSnapshot(newOwner.name());
            pet.setOwnerPhoneSnapshot(newOwner.phone());
        }
        if (request.breed() != null) {
            pet.setBreed(blankToNull(request.breed()));
        }
        if (request.gender() != null) {
            pet.setGender(request.gender());
        }
        if (request.dateOfBirth() != null) {
            pet.setDateOfBirth(request.dateOfBirth());
        }
        if (request.color() != null) {
            pet.setColor(blankToNull(request.color()));
        }
        if (request.neutered() != null) {
            pet.setNeutered(request.neutered());
        }
        if (request.microchipNumber() != null) {
            String microchip = blankToNull(request.microchipNumber());
            if (microchip != null && petRepository.existsByMicrochipNumberAndIdNot(microchip, id)) {
                throw new AppException(ErrorCode.CODE_ALREADY_EXISTS, "Số vi mạch đã được dùng cho thú cưng khác");
            }
            pet.setMicrochipNumber(microchip);
        }
        if (request.identifyingCharacteristics() != null) {
            pet.setIdentifyingCharacteristics(blankToNull(request.identifyingCharacteristics()));
        }
        if (request.allergies() != null) {
            pet.setAllergies(blankToNull(request.allergies()));
        }
        if (request.notes() != null) {
            pet.setNotes(blankToNull(request.notes()));
        }
        Pet saved = petRepository.saveAndFlush(pet);
        // Đổi tên hoặc chuyển chủ: các service khác giữ snapshot (lịch hẹn: tên thú cưng, chủ nuôi) -> phát sự kiện cùng giao dịch
        if (!Objects.equals(oldName, saved.getName()) || !Objects.equals(oldOwner, saved.getOwnerId())) {
            outboxWriter.write(AGGREGATE, saved.getId(), new PetUpdatedEvent(UuidV7.generate(clock), clock.instant(), actor.id(),
                    saved.getId(), saved.getName(), saved.getOwnerId(), saved.getOwnerNameSnapshot(), saved.getOwnerPhoneSnapshot()));
        }
        return withLatestWeight(mapper.toResponse(saved));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Actor actor = currentActor();
        if (actor.role() != RoleCode.PET_OWNER && actor.role() != RoleCode.RECEPTIONIST) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        Pet pet = findPet(id);
        assertOwnerAccess(actor, pet);
        if (visitRepository.existsByPetIdAndStatusIn(id, VisitStatus.ACTIVE)) {
            throw new AppException(ErrorCode.PET_HAS_OPEN_VISIT, "Thú cưng đang có đợt khám chưa kết thúc nên chưa xóa được hồ sơ");
        }
        pet.setDeletedAt(clock.instant());   // xóa mềm: bệnh án cũ vẫn tra được
        petRepository.save(pet);
        log.info("Xóa mềm thú cưng {} bởi userId={}", id, actor.id());
    }

    @Override
    @Transactional(readOnly = true)
    public PetResponse getById(UUID id) {
        Pet pet = findPet(id);
        assertOwnerAccess(currentActor(), pet);
        return withLatestWeight(mapper.toResponse(pet));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PetResponse> search(UUID ownerId, String ownerPhone, String keyword, int page, int size) {
        Actor actor = currentActor();
        UUID effectiveOwner = actor.role() == RoleCode.PET_OWNER ? actor.id() : ownerId;
        String phone = actor.role() == RoleCode.PET_OWNER ? null : blankToNull(ownerPhone);

        Specification<Pet> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (effectiveOwner != null) {
                predicates.add(cb.equal(root.get("ownerId"), effectiveOwner));
            }
            if (phone != null) {
                // Tìm theo SĐT dùng snapshot trên chính hồ sơ: không gọi auth-service, không JOIN chéo CSDL
                predicates.add(cb.equal(root.get("ownerPhoneSnapshot"), phone));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("name")), like, '\\'),
                        cb.like(cb.lower(root.get("petCode")), like, '\\'),
                        cb.like(cb.lower(root.get("microchipNumber")), like, '\\')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        // UUIDv7 xếp được theo thời gian: sắp theo id giảm dần = mới tạo trước
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<Pet> result = petRepository.findAll(spec, pageable);
        Map<UUID, BigDecimal> weights = latestWeights(result.getContent().stream().map(Pet::getId).toList());
        return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isLast(), p -> mapper.toResponse(p).withWeight(weights.get(p.getId())));
    }

    /** Cân nặng hiện tại = weight_at_visit của đợt khám gần nhất, null nếu chưa từng được cân. */
    private PetResponse withLatestWeight(PetResponse pet) {
        return pet.withWeight(latestWeights(List.of(pet.id())).get(pet.id()));
    }

    private Map<UUID, BigDecimal> latestWeights(Collection<UUID> petIds) {
        if (petIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BigDecimal> result = new HashMap<>();
        for (Object[] row : visitRepository.findLatestWeights(petIds)) {
            result.put((UUID) row[0], (BigDecimal) row[1]);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisitResponse> getVisits(UUID petId) {
        Pet pet = findPet(petId);
        assertOwnerAccess(currentActor(), pet);
        return assembler.summaries(visitRepository.findByPetIdOrderByIdDesc(petId));
    }

    @Override
    @Transactional(readOnly = true)
    public PetSummaryResponse getSummary(UUID id) {
        Pet pet = petRepository.findWithSpeciesByIdAndDeletedAtIsNull(id).orElseThrow(() -> new AppException(ErrorCode.PET_NOT_FOUND));
        return new PetSummaryResponse(pet.getId(), pet.getOwnerId(), pet.getName(), pet.getOwnerNameSnapshot(), pet.getOwnerPhoneSnapshot());
    }

    @Override
    @Transactional
    public int refreshOwnerSnapshot(UUID ownerId, String ownerName, String ownerPhone) {
        if (ownerId == null || ownerName == null || ownerPhone == null) {
            return 0;
        }
        return petRepository.updateOwnerSnapshot(ownerId, ownerName, ownerPhone);
    }

    // ------------------------------------------------------------------ helper

    private Pet findPet(UUID id) {
        return petRepository.findWithSpeciesByIdAndDeletedAtIsNull(id).orElseThrow(() -> new AppException(ErrorCode.PET_NOT_FOUND));
    }

    /** PET-yyyy-XXXXXX: khóa chính là UUID nên mã hiển thị dùng đuôi ngẫu nhiên; kiểm tra trùng rồi thử lại (UNIQUE là chốt chặn cuối). */
    private String newPetCode() {
        String prefix = "PET-" + LocalDate.now(clock).getYear() + "-";
        for (int i = 0; i < CODE_ATTEMPTS; i++) {
            String code = prefix + Codes.randomSuffix(6);
            if (!petRepository.existsByPetCode(code)) {
                return code;
            }
        }
        throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION, "Không sinh được mã hồ sơ thú cưng duy nhất, vui lòng thử lại");
    }

    /** Chủ nuôi chỉ thao tác được trên thú cưng của mình; của người khác trả "không tìm thấy" để không lộ sự tồn tại. */
    private void assertOwnerAccess(Actor actor, Pet pet) {
        if (actor.role() == RoleCode.PET_OWNER && !pet.getOwnerId().equals(actor.id())) {
            throw new AppException(ErrorCode.PET_NOT_FOUND);
        }
    }

    private Actor currentActor() {
        return new Actor(SecurityUtils.getCurrentUserId(),
                SecurityUtils.getCurrentRole().orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED)));
    }

    /** Tài khoản phải là chủ thú cưng còn hoạt động; trả tên + SĐT để chụp snapshot. Gọi Feign: PHẢI nằm ngoài transaction. */
    private Owner fetchOwner(UUID ownerId) {
        ApiResponse<UserSummaryResponse> response = authClient.getUser(ownerId);
        UserSummaryResponse owner = response == null ? null : response.getData();
        if (owner == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        if (!owner.roles().contains(RoleCode.PET_OWNER)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Người dùng được chọn không phải chủ thú cưng");
        }
        if (!"ACTIVE".equals(owner.status())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tài khoản chủ thú cưng không còn hoạt động");
        }
        return new Owner(owner.id(), owner.fullName(), owner.phoneNumber());
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
